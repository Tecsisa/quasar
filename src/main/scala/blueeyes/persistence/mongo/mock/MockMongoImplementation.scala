package blueeyes.persistence.mongo.mock

import blueeyes.concurrent.ReadWriteLock
import blueeyes.json.JsonAST._
import blueeyes.json.{JPath}
import blueeyes.persistence.mongo._
import blueeyes.persistence.mongo.MongoFilterEvaluator._
import collection.mutable.ConcurrentMap
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConversions._
import scala.collection.immutable.ListSet
import scalaz._
import Scalaz._

class MockMongo() extends Mongo {
  private val databases: ConcurrentMap[String, MockMongoDatabase]     = new ConcurrentHashMap[String, MockMongoDatabase]()
  def database(databaseName: String) = {
    databases.get(databaseName).getOrElse({
      val mongoDatabase  = new MockMongoDatabase(this)
      databases.putIfAbsent(databaseName, mongoDatabase).getOrElse(mongoDatabase)
    })
  }
}

private[mongo] class MockMongoDatabase(val mongo: Mongo) extends MongoDatabase {
  private val databaseCollections: ConcurrentMap[String, MockDatabaseCollection]   = new ConcurrentHashMap[String, MockDatabaseCollection]()

  def collection(collectionName: String) = {
    databaseCollections.get(collectionName).getOrElse({
      val collection  = new MockDatabaseCollection(collectionName, this)
      databaseCollections.putIfAbsent(collectionName, collection).getOrElse(collection)
    })
  }

  protected def poolSize = 1

  def collections = databaseCollections.entrySet.map(entry => MongoCollectionHolder(entry.getValue, entry.getKey, this)).toSet
}

private[mongo] class MockDatabaseCollection(val name: String, val database: MockMongoDatabase) extends DatabaseCollection with JObjectFields with MockIndex with ReadWriteLock {
  private var container = JArray(Nil)

  def insert(objects: List[JObject]): Unit = {
    writeLock{
      index(objects)
      insert0(objects)
    }
  }

  def remove(filter: Option[MongoFilter]) : Unit = {
    writeLock{
      process(filter, remove _)
    }
  }

  def count(filter: Option[MongoFilter]) = safeProcess(filter, {found: List[JObject] => found.size})

  def distinct(selection: JPath, filter: Option[MongoFilter]) =
      safeProcess(filter, {found: List[JObject] => found.map(jobject => selectByPath(selection, jobject, (v) => {Some(v)}, (p, v) => {v})).filter(_.isDefined).map(_.get).distinct})

  def group(selection: MongoSelection, filter: Option[MongoFilter], initial: JObject, reduce: String) =
    safeProcess(filter, {found: List[JObject] => GroupFunction(selection, initial, reduce, found)})

  def mapReduce(map: String, reduce: String, outputCollection: Option[String], filter: Option[MongoFilter]) =
    safeProcess(filter, {found: List[JObject] => MapReduceFunction(map, reduce, outputCollection, found, database)})

  def update(filter: Option[MongoFilter], value : MongoUpdate, upsert: Boolean, multi: Boolean){
    writeLock{
      var (objects, update) = (if (multi) search(filter) else search(filter).headOption.map(_ :: Nil).getOrElse(Nil)) match {
                                case Nil if upsert => value match {
                                  case e: MongoUpdateObject => (List(JObject(Nil)), value)
                                  case _ => (List(JObject(Nil)), value |+| filter.map(FilterToUpdateConvert(_)).getOrElse(MongoUpdateNothing))
                                }
                                case v => (v, value)
                              }
      var updated = UpdateFunction(update, objects)

      if (upsert) remove(objects)

      index(updated)
      remove(objects)
      insert0(updated)
    }
  }

  def select(selection : MongoSelection, filter: Option[MongoFilter], sort: Option[MongoSort], skip: Option[Int], limit: Option[Int], hint: Option[Hint]) = {
    safeProcess(filter, {objects: List[JObject] =>
      val sorted  = sort.map(v => objects.sorted(new JObjectOrdering(v.sortField, v.sortOrder.order))).getOrElse(objects)
      val skipped = skip.map(sorted.drop(_)).getOrElse(sorted)
      val limited = limit.map(skipped.take(_)).getOrElse(skipped)

      val jObjectIterator = selectExistingFields(limited, selection.selection).map(_.asInstanceOf[JObject]).iterator

      new IterableViewImpl[JObject](jObjectIterator)
    })
  }

  def requestStart: Unit = ()

  def requestDone: Unit = ()

  def getLastError: Option[com.mongodb.BasicDBObject] = None

  override def ensureIndex(name: String, keys: ListSet[JPath], unique: Boolean) = {
    writeLock {
      super.ensureIndex(name, keys, unique)
    }
  }

  override def dropIndexes = {
    writeLock{
      super.dropIndexes
    }
  }

  override def dropIndex(name: String){
    writeLock{
      super.dropIndex(name)
    }
  }

  def indexed = all

  private def safeProcess[T](filter: Option[MongoFilter], f: (List[JObject]) => T): T = {
    readLock{
      process(filter, f)
    }
  }
  private def process[T](filter: Option[MongoFilter], f: (List[JObject]) => T): T = f(search(filter))

  private def search(filter: Option[MongoFilter]) = filter.map(all.filter(_).map(_.asInstanceOf[JObject])).getOrElse(all)

  private def insert0(objects: List[JObject]) = container = JArray(container.elements ++ objects)

  private def all: List[JObject] = container.elements.map(_.asInstanceOf[JObject])

  private def remove(objects: List[JObject]): Unit = container = JArray(all filterNot (objects contains))
}

private[mock] object FilterToUpdateConvert{
  import MongoFilterOperators._

  def apply(filter: MongoFilter):  MongoUpdate = filter match{
    case MongoFilterAll               => MongoUpdateNothing
    case x : MongoOrFilter            => MongoUpdateNothing
    case x : MongoElementsMatchFilter => MongoUpdateNothing
    case x : MongoFieldFilter         => x.operator match {
      case $eq  => x.lhs set x.rhs
      case _    => MongoUpdateNothing
    }
    case x : MongoAndFilter           => x.queries.map(FilterToUpdateConvert(_)).asMA.sum
  }
}

private[mongo] class MockMapReduceOutput(output: MockDatabaseCollection) extends MapReduceOutput {
  override def outputCollection = MongoCollectionHolder(output, output.name, output.database)
  override def drop = {}
}
