package com.shocktrade.models.quote

import akka.actor.Props
import akka.pattern.ask
import akka.routing.RoundRobinPool
import akka.util.Timeout
import com.ldaniels528.commons.helpers.OptionHelper._
import com.shocktrade.actors.QuoteMessages._
import com.shocktrade.actors.WebSockets.QuoteUpdated
import com.shocktrade.actors.{DBaseQuoteActor, RealTimeQuoteActor, WebSockets}
import com.shocktrade.controllers.Application._
import com.shocktrade.util.BSONHelper._
import com.shocktrade.util.{ConcurrentCache, DateUtil}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json.{obj => JS}
import play.api.libs.json.{JsArray, JsObject}
import play.libs.Akka
import play.modules.reactivemongo.json.collection.JSONCollection
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{BSONArray, BSONDocument => BS}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Stock Quote Proxy
 * @author lawrence.daniels@gmail.com
 */
object StockQuotes {
  private val realTimeCache = ConcurrentCache[String, JsObject](1.minute)
  private val diskCache = ConcurrentCache[String, JsObject](4.hours)
  private val system = Akka.system
  private val quoteActor = system.actorOf(Props[RealTimeQuoteActor].withRouter(RoundRobinPool(nrOfInstances = 50)), name = "QuoteRealTime")
  private val mongoReader = system.actorOf(Props[DBaseQuoteActor].withRouter(RoundRobinPool(nrOfInstances = 50)), name = "QuoteReader")
  private val mongoWriter = system.actorOf(Props[DBaseQuoteActor], name = "QuoteWriter")
  private lazy val mcQBS = db.collection[BSONCollection]("Stocks")
  implicit val timeout: Timeout = 45.seconds
  lazy val mcQ = db.collection[JSONCollection]("Stocks")

  import system.dispatcher

  def init(fields: JsObject): Unit = {
    mcQ.find(JS("active" -> true), fields).cursor[JsObject].collect[Seq]() foreach { objects =>
      Logger.info(s"Pre-loaded ${objects.length} quote(s)")
      objects foreach { jo =>
        for {
          symbol <- (jo \ "symbol").asOpt[String]
        } {
          diskCache.put(symbol, jo)
        }
      }
    }
  }

  def findQuotes(filter: QuoteFilter): Future[Seq[JsObject]] = {
    (mongoReader ? FindQuotes(filter)) map {
      case e: Throwable => throw new IllegalStateException(e)
      case response => response.asInstanceOf[Seq[JsObject]]
    }
  }

  /**
   * Retrieves a real-time quote for the given symbol
   * @param symbol the given symbol (e.g. 'AAPL')
   * @param ec the given [[ExecutionContext]]
   * @return a promise of an option of a [[JsObject quote]]
   */
  def findRealTimeQuote(symbol: String)(implicit ec: ExecutionContext): Future[Option[JsObject]] = {

    def relayQuote(task: Future[Option[JsObject]]) = {
      task.foreach(_ foreach { quote =>
        realTimeCache.put(symbol, quote, if (DateUtil.isTradingActive) 1.minute else 15.minute)
        WebSockets ! QuoteUpdated(quote)
        mongoWriter ! SaveQuote(symbol, quote)
      })
      task
    }

    val mySymbol = symbol.toUpperCase.trim
    if (DateUtil.isTradingActive) relayQuote(findRealTimeQuoteFromService(mySymbol))
    else
      realTimeCache.get(mySymbol) match {
        case quote@Some(_) => Future.successful(quote)
        case None =>
          relayQuote(findRealTimeQuoteFromService(mySymbol))
      }
  }

  def findRealTimeQuotes(symbols: Seq[String])(implicit ec: ExecutionContext): Future[Seq[JsObject]] = {
    val quotes = Future.sequence(symbols map findRealTimeQuote)
    quotes.map(_.flatten)
  }

  def findRealTimeQuoteFromService(symbol: String)(implicit ec: ExecutionContext): Future[Option[JsObject]] = {
    (quoteActor ? GetQuote(symbol)).mapTo[Option[JsObject]]
  }

  /**
   * Retrieves a database quote for the given symbol
   * @param symbol the given symbol (e.g. 'AAPL')
   * @param ec the given [[ExecutionContext]]
   * @return a promise of an option of a [[JsObject quote]]
   */
  def findDBaseQuote(symbol: String)(implicit ec: ExecutionContext): Future[Option[JsObject]] = {
    val mySymbol = symbol.toUpperCase.trim
    diskCache.get(mySymbol) match {
      case quote@Some(_) => Future.successful(quote)
      case None =>
        val quote = (mongoReader ? GetQuote(mySymbol)).mapTo[Option[JsObject]]
        quote.foreach(_ foreach (diskCache.put(mySymbol, _)))
        quote
    }
  }

  def findDBaseFullQuote(symbol: String)(implicit ec: ExecutionContext): Future[Option[JsObject]] = {
    (mongoReader ? GetFullQuote(symbol)).mapTo[Option[JsObject]]
  }

  /**
   * Retrieves a database quote for the given symbol
   * @param symbols the given collection of symbols (e.g. 'AAPL', 'AMD')
   * @param ec the given [[ExecutionContext]]
   * @return a promise of an option of a [[JsObject quote]]
   */
  def findDBaseQuotes(symbols: Seq[String])(implicit ec: ExecutionContext): Future[JsArray] = {
    // first, get as many of the quote from the cache as we can
    val cachedQuotes = symbols flatMap diskCache.get
    val remainingSymbols = symbols.filterNot(diskCache.contains)
    if (remainingSymbols.isEmpty) Future.successful(JsArray(cachedQuotes))
    else {
      // query any remaining quotes from disk
      val task = (mongoReader ? GetQuotes(remainingSymbols)).mapTo[JsArray]
      task.foreach { case JsArray(values) =>
        values foreach { js =>
          (js \ "symbol").asOpt[String].foreach(diskCache.put(_, js.asInstanceOf[JsObject]))
        }
      }
      task
    }
  }

  /**
   * Retrieves a complete quote; the composition of real-time quote and a disc-based quote
   * @param symbol the given ticker symbol
   * @param ec the given [[ExecutionContext]]
   * @return the [[Future promise]] of an option of a [[JsObject quote]]
   */
  def findFullQuote(symbol: String)(implicit ec: ExecutionContext): Future[Option[JsObject]] = {
    val mySymbol = symbol.toUpperCase.trim
    val rtQuoteFuture = findRealTimeQuote(mySymbol)
    val dbQuoteFuture = findDBaseFullQuote(mySymbol)
    for {
      rtQuote <- rtQuoteFuture
      dbQuote <- dbQuoteFuture
    } yield rtQuote.map(q => dbQuote.getOrElse(JS()) ++ q) ?? dbQuote
  }

  def findQuotes(symbols: Seq[String])(fields: String*)(implicit ec: ExecutionContext): Future[Seq[JsObject]] = {
    mcQ.find(JS("symbol" -> JS("$in" -> symbols)), fields.toJsonFields).cursor[JsObject].collect[Seq]()
  }

  def getSymbolsForCsvUpdate(implicit ec: ExecutionContext): Future[Seq[BS]] = {
    mcQBS.find(BS("active" -> true, "$or" -> BSONArray(Seq(
      BS("yfDynLastUpdated" -> BS("$exists" -> false)),
      BS("yfDynLastUpdated" -> BS("$lte" -> new DateTime().minusMinutes(15)))
    ))), BS("symbol" -> 1))
      .cursor[BS]
      .collect[Seq]()
  }

  def getSymbolsForKeyStatisticsUpdate(implicit ec: ExecutionContext): Future[Seq[BS]] = {
    mcQBS.find(BS("active" -> true, "$or" -> BSONArray(Seq(
      BS("yfKeyStatsLastUpdated" -> BS("$exists" -> false)),
      BS("yfKeyStatsLastUpdated" -> BS("$lte" -> new DateTime().minusDays(2)))
    ))), BS("symbol" -> 1))
      .cursor[BS]
      .collect[Seq]()
  }

  def updateQuote(symbol: String, doc: BS): Unit = {
    mcQBS.update(BS("symbol" -> symbol), BS("$set" -> doc))
  }

}
