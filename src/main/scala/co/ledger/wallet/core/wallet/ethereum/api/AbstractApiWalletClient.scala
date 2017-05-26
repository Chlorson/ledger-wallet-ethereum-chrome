package co.ledger.wallet.core.wallet.ethereum.api

import co.ledger.wallet.core.concurrent.AsyncCursor
import co.ledger.wallet.core.device.utils.{EventEmitter, EventReceiver}
import co.ledger.wallet.core.net.{HttpException, WebSocketFactory}
import co.ledger.wallet.core.utils.{DerivationPath, HexUtils}
import co.ledger.wallet.core.wallet.ethereum.Wallet.{GasPriceChanged, StartSynchronizationEvent, StopSynchronizationEvent, WalletNotSetupException}
import co.ledger.wallet.core.wallet.ethereum._
import co.ledger.wallet.core.wallet.ethereum.database.{AccountRow, DatabaseBackedWalletClient}
import co.ledger.wallet.core.wallet.ethereum.events.{NewBlock, NewTransaction}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js.timers
import scala.util.{Failure, Success}

/**
  *
  * AbstractApiWalletClient
  * ledger-wallet-ethereum-chrome
  *
  * Created by Pierre Pollastri on 14/06/2016.
  *
  * The MIT License (MIT)
  *
  * Copyright (c) 2016 Ledger
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  *
  */
abstract class AbstractApiWalletClient(override val name: String,
                                       override val bip44CoinType: String,
                                       override val coinPathPrefix: String) extends Wallet with DatabaseBackedWalletClient {

  implicit val ec: ExecutionContext

  def transactionRestClient: AbstractTransactionRestClient

  def blockRestClient: AbstractBlockRestClient

  def websocketFactory: WebSocketFactory

  override def account(index: Int): Future[Account] = accounts().map(_ (index))

  override def mostRecentBlock(): Future[Block] = init() flatMap { (_) =>

    queryLastBlock() map { (block) =>
      if (block == null)
        throw WalletNotSetupException()
      else
        block
    }
  }

  override def stop(): Unit = {
    init() foreach { (_) =>
      _stopped = true
      _webSocketNetworkObserver.get.stop()
      eventEmitter.unregister(_eventReceiver)
    }
  }

  override def operations(from: Int, batchSize: Int): Future[AsyncCursor[Operation]] = ???

  override def synchronize(): Future[Unit] = {
    _synchronizationFuture.getOrElse({
      eventEmitter.emit(StartSynchronizationEvent())
      _synchronizationFuture = Some(performSynchronize())
      _synchronizationFuture.get
    })
  }

  def performSynchronize(): Future[Unit] = {

    def synchronizeUntilEmptyAccount(syncToken: String, from: Int): Future[Unit] = {
      init().flatMap { (_) =>
        val accounts = _accounts.slice(from, _accounts.length)
        Future.sequence(accounts.map(_.synchronize(syncToken)).toSeq)
      } flatMap { (_) =>
        _accounts.last.countOperations().flatMap {(count) =>
          println(s"Account (${_accounts.length - 1}) has $count operations")
          if (count != 0) {
            val newAccountIndex = _accounts.length
            createAccount(newAccountIndex) flatMap { (_) =>
              synchronizeUntilEmptyAccount(syncToken, 0)
            }
          } else {
            Future.successful()
          }
        }
      } recoverWith {
        case HttpException(_, response, _) =>
          if (response.statusCode == 404) {
            handleReorg() flatMap { (_) =>
              performSynchronize()
            }
          }
          else {
            Future.failed(new Exception("API error"))
          }
        case other => Future.failed(other)
      }
    }
    transactionRestClient.obtainSyncToken() flatMap { (token) =>
      synchronizeUntilEmptyAccount(token, 0)
    } andThen {
      case all =>
        eventEmitter.emit(StopSynchronizationEvent())
        _synchronizationFuture = None
    }
  }

  private def handleReorg(): Future[Unit] = {
    // Get the synchronization hashes
    // Get the corresponding blocks
    // Get all block above or equal to the highest block
    // Fetch all transactions in the highest blocks
    // Open a cursor on the operations corresponding to the previous transactions
    // Delete operations
    // Open a cursor on the previous transactions
    // Delete transactions
    // Open a cursor on the previous blocks
    // Delete blocks
    // End
    Future.sequence(_accounts.map(_.synchronizationBlockHash()).toSeq) flatMap { (hashes) =>
      queryBlocks(hashes.filter(_.isDefined).map(_.get).toArray)
    } flatMap { (blocks) =>
      if (blocks.isEmpty) {
        Future.successful()
      } else {
        queryTransactions(blocks.maxBy(_.height).height) flatMap { (transactions) =>
          val hashes = transactions.map(_.hash)
          deleteOperations(hashes) flatMap { (_) => deleteTransactions(hashes) }
        } flatMap { (_) =>
          deleteBlocks(blocks.map(_.hash))
        } flatMap { (_) =>
          queryLastBlock()
        } flatMap { (block) =>
          Future.sequence(_accounts.map(_.setSynchronizationBlock(block)).toSeq)
        } map { (_) =>
          ()
        }
      }
    }
    null
  }

  private def fetchGasPrice(): Unit = {
    import timers._
    if (!_stopped) {
      transactionRestClient.getEstimatedGasPrice() onComplete {
        case Success(price) =>
          if (price.toBigInt != _gasPrice.toBigInt) {
            eventEmitter.emit(GasPriceChanged(price))
          }
          _gasPrice = price
        case Failure(ex) =>
          setTimeout(5 * 60 * 1000) {
            fetchGasPrice()
          }
      }
    }
  }

  override def estimatedGasPrice(): Future[Ether] = init().map((_) => _gasPrice)

  def ethereumAccountProvider: EthereumAccountProvider

  override def accounts(): Future[Array[Account]] = init().map((_) => _accounts.asInstanceOf[Array[Account]])

  override def isSynchronizing(): Future[Boolean] = init().map((_) => _synchronizationFuture.isDefined)

  override def balance(): Future[Ether] = accounts().flatMap { (accounts) =>
    Future.sequence(accounts.map(_.balance()).toSeq)
  } map { (balances) =>
    var result = Ether.Zero
    for (balance <- balances) {
      result = result + balance
    }
    result
  }

  override def eventEmitter: EventEmitter

  override def pushTransaction(transaction: Array[Byte]): Future[Unit] = init() flatMap { (_) =>
    println("PUSH TX " + HexUtils.encodeHex(transaction))
    transactionRestClient.pushTransaction(transaction)
  }

  private def createAccount(index: Int): Future[Account] = {
    ethereumAccountProvider.getEthereumAccount(DerivationPath(s"44'/$bip44CoinType'${coinPathPrefix}/0'/$index")).map { (ethereumAccount) =>
      val account = new AccountRow(index, ethereumAccount.toString)
      putAccount(account)
      _accounts = _accounts :+ newAccountClient(account)
      _accounts.last
    }
  }

  private def init(): Future[Unit] = {
    if (_stopped)
      Future.failed(new Exception("Client is stopped"))
    else {
      _initPromise.getOrElse({
        _initPromise = Some(Promise[Unit]())
        _initPromise.get.completeWith(queryAccounts(0, Int.MaxValue) flatMap { (accounts) =>
          _accounts = accounts.map(newAccountClient(_))
          _webSocketNetworkObserver = Some(new WebSocketNetworkObserver(websocketFactory, eventEmitter, transactionRestClient, ec))
          _webSocketNetworkObserver.get.start()
          fetchGasPrice()
          if (_accounts.length == 0) {
            createAccount(0).map((_) => ())
          } else {
            Future.successful()
          }
        })
        eventEmitter.register(_eventReceiver)
        _initPromise.get
      }).future
    }
  }

  private val _eventReceiver = new EventReceiver {
    override def receive: Receive = {
      case NewTransaction(tx) =>
        accounts() foreach { (acccounts) =>
          acccounts foreach { (account) =>
            account.asInstanceOf[AbstractApiAccountClient].putTransaction(tx)
          }
        }
      case NewBlock(block) =>
        block.transactionsHashes foreach { (hashes) =>
          queryTransactions(hashes) foreach { (txs) =>
            if (txs.nonEmpty) {
              synchronize()
            }
          }
        }
      case drop =>
    }
  }

  private var _accounts: Array[AbstractApiAccountClient] = null
  private var _initPromise: Option[Promise[Unit]] = None
  private var _stopped = false
  private var _synchronizationFuture: Option[Future[Unit]] = None
  private var _webSocketNetworkObserver: Option[WebSocketNetworkObserver] = None
  private var _gasPrice = Ether(21000000000L)

  protected def newAccountClient(accountRow: AccountRow): AbstractApiAccountClient
}
