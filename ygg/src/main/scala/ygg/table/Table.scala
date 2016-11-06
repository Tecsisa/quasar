/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ygg.table

import ygg._, common._, json._
import trans._
import quasar._
import scalaz._, Scalaz._
import JDBM.{ SortedSlice, IndexMap }

private object addGlobalIdScanner extends Scanner {
  type A = Long
  val init = 0l
  def scan(a: Long, cols: ColumnMap, range: Range): A -> ColumnMap = {
    val globalIdColumn = new RangeColumn(range) with LongColumn { def apply(row: Int) = a + row }
    (a + range.end + 1, cols + (ColumnRef(CPath(CPathIndex(1)), CLong) -> globalIdColumn))
  }
}

final case class TableRep[T](table: T, convert: T => TableMethods[T], companion: OldTableCompanion[T]) {
  implicit def tableToMethods(table: T): TableMethods[T] = convert(table)
}

trait Table extends TableMethods[Table] {
  def asRep: TableRep[Table] = TableRep[Table](this, x => x, companion)
  def self: Table            = this
  def companion: Table.type  = Table
}
object Table extends OldTableCompanion[Table] {
  def fromSlice(slice: Slice): Table                               = new InternalTable(slice)
  def fromSlices(slices: NeedSlices, size: TableSize): Table       = new ExternalTable(slices, size)
  def newInternalTable(slice: Slice): InternalTable                = new InternalTable(slice)
  def newExternalTable(slices: NeedSlices, size: TableSize): Table = new ExternalTable(slices, size)
  def empty: Table                                                 = Table(emptyStreamT(), ExactSize(0))

  implicit def tableMethods(table: Table): TableMethods[Table] = table
}

trait OldTableCompanion[T] extends TableMethodsCompanion[T] {
  type M[+X] = Need[X]
  type Table = T

  implicit def tableMethods(table: T): TableMethods[T]

  private implicit def liftRep(table: T): TableRep[T] = TableRep(table, tableMethods, this)

  lazy val sortMergeEngine = new MergeEngine

  def newInternalTable(slice: Slice): T
  def newExternalTable(slices: NeedSlices, size: TableSize): T
  def empty: T

  def addGlobalId(spec: TransSpec1): TransSpec1                                                = Scan(WrapArray(spec), addGlobalIdScanner)
  def align(sourceL: Table, alignL: TransSpec1, sourceR: T, alignR: TransSpec1): PairOf[Table] = AlignTable(sourceL, alignL, sourceR, alignR)

  def loadTable(mergeEngine: MergeEngine, indices: IndexMap, sortOrder: DesiredSortOrder): T = {
    import mergeEngine._
    val totalCount = indices.toList.map { case (_, sliceIndex) => sliceIndex.count }.sum

    // Map the distinct indices into SortProjections/Cells, then merge them
    def cellsMs: Stream[Need[Option[CellState]]] = indices.values.toStream.zipWithIndex map {
      case (SortedSlice(name, kslice, vslice, _, _, _, _), index) =>
        val slice = Slice(kslice.size, kslice.wrap(CPathIndex(0)).columns ++ vslice.wrap(CPathIndex(1)).columns)
        // We can actually get the last key, but is that necessary?
        Need(Some(CellState(index, new Array[Byte](0), slice, (k: Bytes) => Need(None))))

      case (JDBM.SliceIndex(name, dbFile, _, _, _, keyColumns, valColumns, count), index) =>
        // Elided untested code.
        ???
    }

    val head = StreamT.Skip(
      StreamT.wrapEffect(
        for (cellOptions <- cellsMs.sequence) yield {
          mergeProjections(sortOrder, cellOptions.flatMap(a => a)) { slice =>
            // only need to compare on the group keys (0th element of resulting table) between projections
            slice.columns.keys collect { case ColumnRef(path @ CPath(CPathIndex(0), _ @_ *), _) => path }
          }
        }
      )
    )

    apply(StreamT(Need(head)), ExactSize(totalCount)) transform TransSpec1.DerefArray1
  }

  /**
    * Sorts the KV table by ascending or descending order based on a seq of transformations
    * applied to the rows.
    *
    * @see quasar.ygg.TableModule#groupByN(TransSpec1, DesiredSortOrder, Boolean)
    */
  private def groupExternalByN(table: T, groupKeys: Seq[TransSpec1], valueSpec: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean): Need[Seq[T]] = {
    WriteTable.writeSorted(table, groupKeys, valueSpec, sortOrder, unique) map {
      case (streamIds, indices) =>
        val streams = indices.groupBy(_._1.streamId)
        streamIds.toStream map { streamId =>
          streams get streamId map (loadTable(sortMergeEngine, _, sortOrder)) getOrElse empty
        }
    }
  }

  def sort[F[_]: Monad](table: T, key: TransSpec1, order: DesiredSortOrder): F[T]       = sortCommon[F](table, key, order, unique = false)
  def sortUnique[F[_]: Monad](table: T, key: TransSpec1, order: DesiredSortOrder): F[T] = sortCommon[F](table, key, order, unique = true)

  private def sortCommon[F[_]: Monad](table: T, key: TransSpec1, order: DesiredSortOrder, unique: Boolean): F[T] =
    groupByN[F](externalize(table), Seq(key), root, order, unique) map (_.headOption getOrElse empty)

  /**
    * Sorts the KV table by ascending or descending order based on a seq of transformations
    * applied to the rows.
    *
    * @param keys The transspecs to use to obtain the values to sort on
    * @param values The transspec to use to obtain the non-sorting values
    * @param order Whether to sort ascending or descending
    * @param unique If true, the same key values will sort into a single row, otherwise
    * we assign a unique row ID as part of the key so that multiple equal values are
    * preserved
    */
  def groupByN[F[_]: Monad](table: T, keys: Seq[TransSpec1], values: TransSpec1, order: DesiredSortOrder, unique: Boolean): F[Seq[T]] =
    ().point[F] map (_ => groupExternalByN(externalize(table), keys, values, order, unique).value)

  def writeAlignedSlices(kslice: Slice, vslice: Slice, jdbmState: JDBMState, indexNamePrefix: String, sortOrder: DesiredSortOrder) =
    WriteTable.writeAlignedSlices(kslice, vslice, jdbmState, indexNamePrefix, sortOrder)

  /**
    * Passes over all slices and returns a new slices that is the concatenation
    * of all the slices. At some point this should lazily chunk the slices into
    * fixed sizes so that we can individually sort/merge.
    */
  def reduceSlices(slices: NeedSlices): NeedSlices = {
    def rec(ss: List[Slice], slices: NeedSlices): NeedSlices = {
      StreamT[Need, Slice](slices.uncons map {
        case Some((head, tail)) => StreamT.Skip(rec(head :: ss, tail))
        case None if ss.isEmpty => StreamT.Done
        case None               => StreamT.Yield(Slice.concat(ss.reverse), emptyStreamT())
      })
    }

    rec(Nil, slices)
  }

  def load(table: Table, tpe: JType, projections: Map[Path, Projection]): M[Table] = {
    val reduced = table reduce new CReducer[Set[Path]] {
      def reduce(schema: CSchema, range: Range): Set[Path] = schema columns JTextT flatMap {
        case s: StrColumn => range collect { case i if s isDefinedAt i => Path(s(i)) }
        case _            => Set()
      }
    }
    reduced map { paths =>
      val projs = paths.toList flatMap projections.get
      apply(
        projs foldMap (_ getBlockStreamForType tpe),
        ExactSize(projs.foldMap(_.length)(Monoid[Long]))
      )
    }
  }

  def constSliceTable[A: CValueType](vs: Array[A], mkColumn: Array[A] => Column): Table = apply(
    singleStreamT(Slice(vs.length, columnMap(ColumnRef.id(CValueType[A]) -> mkColumn(vs)))),
    ExactSize(vs.length)
  )
  def constSingletonTable(singleType: CType, column: Column): Table = apply(
    singleStreamT(Slice(1, columnMap(ColumnRef.id(singleType) -> column))),
    ExactSize(1)
  )

  def constBoolean(v: Set[Boolean]): Table    = constSliceTable[Boolean](v.toArray, ArrayBoolColumn(_))
  def constLong(v: Set[Long]): Table          = constSliceTable[Long](v.toArray, ArrayLongColumn(_))
  def constDouble(v: Set[Double]): Table      = constSliceTable[Double](v.toArray, ArrayDoubleColumn(_))
  def constDecimal(v: Set[BigDecimal]): Table = constSliceTable[BigDecimal](v.toArray, ArrayNumColumn(_))
  def constString(v: Set[String]): Table      = constSliceTable[String](v.toArray, ArrayStrColumn(_))
  def constDate(v: Set[DateTime]): Table      = constSliceTable[DateTime](v.toArray, ArrayDateColumn(_))
  def constNull: Table                        = constSingletonTable(CNull, new InfiniteColumn with NullColumn)
  def constEmptyObject: Table                 = constSingletonTable(CEmptyObject, new InfiniteColumn with EmptyObjectColumn)
  def constEmptyArray: Table                  = constSingletonTable(CEmptyArray, new InfiniteColumn with EmptyArrayColumn)

  def merge(grouping: GroupingSpec[T])(body: (RValue, GroupId => M[T]) => M[T]): M[T] = MergeTable[T](grouping)(body)

  def cogroup(self: Table, leftKey: TransSpec1, rightKey: TransSpec1, that: Table)(leftResultTrans: TransSpec1, rightResultTrans: TransSpec1, bothResultTrans: TransSpec2): Table =
    CogroupTable(self, leftKey, rightKey, that)(leftResultTrans, rightResultTrans, bothResultTrans)

  def externalize(table: T): T = newExternalTable(table.slices, table.size)

  def fromData(data: Vector[Data]): Table          = fromJValues(data map dataToJValue)
  def fromFile(file: jFile): Table                 = fromJValues((JParser parseManyFromFile file).orThrow)
  def fromString(json: String): Table              = fromJValues(Seq(JParser parseUnsafe json))
  def toJson(dataset: Table): Need[Stream[JValue]] = dataset.toJson.map(_.toStream)
  def toJsonSeq(table: Table): Seq[JValue]         = toJson(table).copoint

  def fromRValues(values: Stream[RValue], maxSliceSize: Option[Int]): Table = {
    val sliceSize = maxSliceSize.getOrElse(yggConfig.maxSliceSize)

    def makeSlice(data: Stream[RValue]): Slice -> Stream[RValue] =
      data splitAt sliceSize leftMap (Slice fromRValues _)

    fromSlices(
      unfoldStream(values)(events => Need(events.nonEmpty option makeSlice(events.toStream))),
      ExactSize(values.length)
    )
  }
}
