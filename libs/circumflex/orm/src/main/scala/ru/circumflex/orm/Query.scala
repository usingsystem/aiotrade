package ru.circumflex.orm

import ORM._
import collection.mutable.ListBuffer
import java.sql.{PreparedStatement, ResultSet}
import java.util.regex.Pattern
import ru.circumflex.orm.DMLQuery.Delete
import ru.circumflex.orm.DMLQuery.NativeDMLQuery
import ru.circumflex.orm.DMLQuery.Update
import ru.circumflex.orm.Predicate.AggregatePredicate
import ru.circumflex.orm.Predicate.EmptyPredicate
import ru.circumflex.orm.Predicate.SimpleExpression
import ru.circumflex.orm.Predicate.SimpleExpressionHelper
import ru.circumflex.orm.Predicate.SubqueryExpression
import ru.circumflex.orm.Projection.AtomicProjection
import ru.circumflex.orm.Projection.ColumnProjection
import ru.circumflex.orm.Projection.CompositeProjection
import ru.circumflex.orm.Projection.ScalarProjection
import ru.circumflex.orm.RelationNode.JoinNode

/**
 * Result set operation (UNION, EXCEPT, INTERSECT, etc.).
 */
abstract class SetOperation(val expression: String)

object Union extends SetOperation("union")
object UnionAll extends SetOperation("union all")
object Except extends SetOperation("except")
object ExceptAll extends SetOperation("except all")
object Intersect extends SetOperation("intersect")
object IntersectAll extends SetOperation("intersect all")

trait Query extends SQLable with JDBCHelper with SQLFragment {
  protected var aliasCounter = 0;

  /**
   * Generate an alias to eliminate duplicates within query.
   */
  protected def nextAlias: String = {
    aliasCounter += 1
    return "this_" + aliasCounter
  }

  /**
   * Sets prepared statement parameters of this query starting from specified index.
   * Returns the new starting index of prepared statement.
   */
  def setParams(st: PreparedStatement, startIndex: Int): Int = {
    var paramsCounter = startIndex;
    parameters.foreach(p => {
        typeConverter.write(st, p, paramsCounter)
        paramsCounter += 1
      })
    return paramsCounter
  }

}

object Query {

  trait SQLQuery extends Query {

    protected var _projections: Seq[Projection[_]] = Nil

    /**
     * Returns the SELECT clause of this query.
     */
    def projections: Seq[Projection[_]] = _projections

    /**
     * Removes all projections from this query.
     */
    def clearProjections: this.type = {
      this._projections = Nil
      return this
    }

    /**
     * Adds specified projections to SELECT clause.
     * All projections with "this" alias are assigned query-unique alias.
     */
    def addProjection(projections: Projection[_]*): this.type = {
      projections.toList.foreach(ensureProjectionAlias(_))
      this._projections ++= projections
      return this
    }

    protected def ensureProjectionAlias[T](projection: Projection[T]): Unit =
      projection match {
        case p: AtomicProjection[_] if (p.alias == "this") => p.as(nextAlias)
        case p: CompositeProjection[_] =>
          p.subProjections.foreach(ensureProjectionAlias(_))
        case _ =>
      }

    protected def findProjection(proj: Projection[_], projList: Seq[Projection[_]]): Option[Projection[_]] = {
      if (projList == Nil) return None
      projList.find(p => p == proj) match {
        case None => findProjection(proj, projList.flatMap {
              case p: CompositeProjection[_] => p.subProjections
              case _ => Nil
            })
        case value => value
      }
    }

    def sqlProjections = projections.toList.map(_.toSql).mkString(", ")

    /* DATA RETRIEVAL STUFF */

    /**
     * Executes a query, opens a JDBC result set and executes provided actions.
     */
    def resultSet[A](actions: ResultSet => A): A = transactionManager.sql(toSql)(st => {
        sqlLog.debug(toSql)
        setParams(st, 1)
        auto(st.executeQuery)(actions)
      })

    /**
     * Reads a tuple from specified result set using query projections.
     */
    def readTuple(rs: ResultSet): Array[Any] =
      projections.map(_.read(rs).getOrElse(null)).toArray

    /**
     * Executes a query and returns a list of tuples, designated by query projections.
     */
    def list(): Seq[Array[Any]] = resultSet(rs => {
        val result = new ListBuffer[Array[Any]]()
        while (rs.next)
          result += readTuple(rs)
        return result
      })

    /**
     * Executes a query and returns a unique result.
     * An exception is thrown if result set yields more than one row.
     */
    def unique(): Option[Array[Any]] = resultSet(rs => {
        if (!rs.next) return None
        else if (rs.isLast) return Some(projections.map(_.read(rs).getOrElse(null)).toArray)
        else throw new ORMException("Unique result expected, but multiple rows found.")
      })

  }

  class NativeSQLQuery(fragment: SQLFragment) extends SQLQuery {
    def parameters = fragment.parameters
    def toSql = fragment.toSql.replaceAll("\\{\\*\\}", sqlProjections)
  }

  class Subselect extends SQLQuery {

    protected var _relations: Seq[RelationNode[_]] = Nil
    protected var _where: Predicate = EmptyPredicate
    protected var _having: Predicate = EmptyPredicate
    protected var _groupBy: Seq[Projection[_]] = Nil
    protected var _setOps: Seq[Pair[SetOperation, Subselect]] = Nil

    def this(nodes: RelationNode[_]*) = {
      this()
      nodes.toList.foreach(addFrom(_))
    }

    /**
     * Returns the FROM clause of this query.
     */
    def relations = _relations

    /**
     * Returns query parameters sequence.
     */
    def parameters: Seq[Any] = _where.parameters ++
    _having.parameters ++
    _setOps.flatMap(p => p._2.parameters)

    /**
     * Returns queries combined with this subselect using specific set operation
     * (in pair, <code>SetOperation -> Subselect</code>),
     */
    def setOps = _setOps

    /**
     * Returns the WHERE clause of this query.
     */
    def where: Predicate = this._where

    /**
     * Sets WHERE clause of this query.
     */
    def where(predicate: Predicate): this.type = {
      this._where = predicate
      return this
    }

    /**
     * Specifies the simple expression to use as the WHERE clause of this query.
     */
    def where(expression: String, params: Pair[String,Any]*): this.type =
      where(prepareExpr(expression, params: _*))

    /**
     * Returns the HAVING clause of this query.
     */
    def having: Predicate = this._having

    /**
     * Sets HAVING clause of this query.
     */
    def having(predicate: Predicate): this.type = {
      this._having = predicate
      return this
    }

    /**
     * Specifies the simple expression to use as the HAVING clause of this query.
     */
    def having(expression: String, params: Any*): this.type =
      having(expr(expression, params.toList: _*))

    /**
     * Returns GROUP BY clause of this query.
     */
    def groupBy: Seq[Projection[_]] = {
      var result = _groupBy
      if (projections.exists(_.grouping_?))
        projections.filter(!_.grouping_?)
      .foreach(p => if (!result.contains(p)) result ++= List(p))
      return result
    }

    /**
     * Sets GROUP BY clause of this query.
     */
    def groupBy(proj: Projection[_] *): this.type = {
      proj.toList.foreach(p => addGroupByProjection(p))
      return this
    }

    /**
     * Adds specified node to FROM clause.
     * All nodes with "this" alias are assigned query-unique alias.
     * All projections are added too.
     */
    def addFrom[R](node: RelationNode[R]): this.type = {
      ensureNodeAlias(node)
      this._relations ++= List(node)
      addProjection(node.projections: _*)
      return this
    }

    protected def ensureNodeAlias[R](node: RelationNode[R]): RelationNode[R] = node match {
      case j: JoinNode[_, _] =>
        ensureNodeAlias(j.left)
        ensureNodeAlias(j.right)
        j
      case n: RelationNode[_] if (n.alias == "this") => node.as(nextAlias)
      case n => n
    }

    /**
     * Adds specified relation to FROM clause (assigning it query-unique alias).
     * All projections are added too.
     */
    def addFrom[R](rel: Relation[R]): this.type =
      addFrom(rel.as("this"))

    protected def addGroupByProjection(proj: Projection[_]): this.type = {
      val pr = findProjection(proj, _projections) match {
        case Some(p) => p
        case _ => {
            addProjection(proj)
            proj
          }
      }
      this._groupBy ++= List[Projection[_]](pr)
      return this
    }

    /* SET OPERATIONS */

    def addSetOp(op: SetOperation, subselect: Subselect): this.type = {
      _setOps ++= List(op -> subselect)
      return this
    }

    def union(subselect: Subselect): this.type =
      addSetOp(Union, subselect)

    def unionAll(subselect: Subselect): this.type =
      addSetOp(UnionAll, subselect)

    def except(subselect: Subselect): this.type =
      addSetOp(Except, subselect)

    def exceptAll(subselect: Subselect): this.type =
      addSetOp(ExceptAll, subselect)

    def intersect(subselect: Subselect): this.type =
      addSetOp(Intersect, subselect)

    def intersectAll(subselect: Subselect): this.type =
      addSetOp(IntersectAll, subselect)

    /* SQL */

    def toSubselectSql = dialect.subselect(this)

    def toSql = toSubselectSql

  }

  class Select extends Subselect {

    protected var _orders: Seq[Order] = Nil
    protected var _limit: Int = -1
    protected var _offset: Int = 0

    def this(nodes: RelationNode[_]*) = {
      this()
      nodes.toList.foreach(addFrom(_))
    }

    override def parameters: Seq[Any] =
      super.parameters ++ _orders.flatMap(_.parameters)

    /**
     * Returns the ORDER BY clause of this query.
     */
    def orders = _orders

    /**
     * Adds an order to ORDER BY clause.
     */
    def orderBy(order: Order*): this.type = {
      this._orders ++= order.toList
      return this
    }

    /**
     * Sets maximum results for this query. Use -1 for infinite-sized queries.
     */
    def limit(value: Int): this.type = {
      _limit = value
      return this
    }

    /**
     * Sets the offset for this query.
     */
    def offset(value: Int): this.type = {
      _offset = value
      return this
    }

    /**
     * Returns this query result limit.
     */
    def limit = this._limit

    /**
     * Returns this query result offset.
     */
    def offset = this._offset

    /**
     * Executes a query and returns the first result.
     * WARNING! This call implicitly sets the query limit to 1. If you plan to reuse
     * the query object after <code>first</code> is called, set query limit manually
     * or it will always yield a single row.
     * </ul>
     */
    def first(): Option[Array[Any]] = {
      limit(1)
      resultSet(rs => {
          if (!rs.next) return None
          else return Some(_projections.map(_.read(rs).getOrElse(null)).toArray)
        })
    }

    override def toSql = dialect.select(this)

  }

  /**
   * Represents an order for queries.
   */
  class Order(val expression: String, val parameters: Seq[Any])

  /**
   * Some common helpers for making up query-related stuff.
   */
  trait QueryHelper {

    /* NODE HELPERS */

    implicit def relationToNode[R](rel: Relation[R]): RelationNode[R] =
      rel.as("this")

    /* ORDER HELPERS */

    def asc(expr: String): Order = new Order(dialect.orderAsc(expr), Nil)

    def asc(proj: ColumnProjection[_, _]): Order = asc(proj.expr)

    def desc(expr: String): Order = new Order(dialect.orderDesc(expr), Nil)

    def desc(proj: ColumnProjection[_, _]): Order = desc(proj.expr)

    implicit def stringToOrder(expr: String): Order =
      new Order(expr, Nil)

    implicit def projectionToOrder(proj: ColumnProjection[_, _]): Order =
      new Order(proj.expr, Nil)

    implicit def predicateToOrder(predicate: Predicate): Order =
      new Order(predicate.toSql, predicate.parameters)

    /* PREDICATE HELPERS */

    implicit def stringToHelper(str: String): SimpleExpressionHelper =
      new SimpleExpressionHelper(str)

    implicit def stringToPredicate(str: String): SimpleExpression =
      new SimpleExpression(str, Nil)

    implicit def columnProjectionToHelper(f: ColumnProjection[_, _]): SimpleExpressionHelper =
      new SimpleExpressionHelper(f.expr)

    implicit def scalarProjectionToHelper(f: ScalarProjection[_]): SimpleExpressionHelper =
      new SimpleExpressionHelper(f.expression)

    def and(predicates: Predicate*) =
      new AggregatePredicate(" and ", predicates.toList)

    def or(predicates: Predicate*) =
      new AggregatePredicate(" or ", predicates.toList)

    def not(predicate: Predicate) =
      new SimpleExpression("not (" + predicate.toSql + ")", predicate.parameters)

    def exists(subselect: Subselect) =
      new SubqueryExpression("exists ", subselect)

    def notExists(subselect: Subselect) =
      new SubqueryExpression("not exists ", subselect)

    def expr(expression: String, params: Any*): SimpleExpression =
      new SimpleExpression(expression, params.toList)

    def prepareExpr(expression: String, params: Pair[String, Any]*): SimpleExpression = {
      var sqlText = expression
      var parameters: Seq[Any] = Nil
      val paramsMap = Map[String, Any](params: _*)
      val pattern = Pattern.compile(":([a-zA-Z_]+)\\b")
      val matcher = pattern.matcher(expression)
      while (matcher.find) paramsMap.get(matcher.group(1)) match {
        case Some(param) => parameters ++= List(param)
        case _ => parameters ++= List(null)
      }
      sqlText = matcher.replaceAll("?")
      return new SimpleExpression(sqlText, parameters)
    }

    /* PROJECTION HELPERS */

    def scalar(expr: String) = new ScalarProjection[Any](expr, false)

    implicit def stringToScalar(expr: String): ScalarProjection[Any] = scalar(expr)

    def count(expr: String) =
      new ScalarProjection[Int]("count(" + expr + ")",  true)
    def countDistinct(expr: String) =
      new ScalarProjection[Int]("count( distinct " + expr + ")", true)
    def max(expr: String) =
      new ScalarProjection[Any]("max(" + expr + ")", true)
    def min(expr: String) =
      new ScalarProjection[Any]("min(" + expr + ")", true)
    def sum(expr: String) =
      new ScalarProjection[Any]("sum(" + expr + ")", true)
    def avg(expr: String) =
      new ScalarProjection[Any]("avg(" + expr + ")", true)

    /* QUERY HELPERS */

    def select(projections: Projection[_]*) = new SelectHelper(projections.toList)

    def update[R](rel: Relation[R]): Update[R] = new Update(rel)

    def delete[R](rel: Relation[R]): Delete[R] = new Delete(rel)

    def sql(sql: String, projections: Projection[_]*) =
      new NativeSQLQueryHelper(sql, projections: _*)

    def dml(dml: String) = new NativeDMLQueryHelper(dml)

  }

  class SelectHelper(val projections: Seq[Projection[_]]) {

    def from(nodes: RelationNode[_]*): Select = {
      val q = new Select(nodes: _*)
      if (projections.size > 0) {
        q.clearProjections
        q.addProjection(projections: _*)
      }
      q
    }

  }

  class NativeSQLQueryHelper(sql: String, projections: Projection[_]*) {
    def prepare(params: Pair[String, Any]*): SQLQuery =
      new NativeSQLQuery(prepareExpr(sql, params: _*)).addProjection(projections: _*)

  }

  class NativeDMLQueryHelper(dml: String) {
    def prepare(params: Pair[String, Any]*): DMLQuery =
      new NativeDMLQuery(prepareExpr(dml, params: _*))
  }
}