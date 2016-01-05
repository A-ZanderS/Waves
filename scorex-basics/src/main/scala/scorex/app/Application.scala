package scorex.app

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import scorex.api.http.{ApiRoute, CompositeHttpServiceActor}
import scorex.block.Block
import scorex.consensus.ConsensusModule
import scorex.network.message.{BasicMessagesRepo, MessageHandler, MessageSpec}
import scorex.network.peer.PeerManager
import scorex.network.{BlockGenerator, HistorySynchronizer, NetworkController, PeerSynchronizer}
import scorex.settings.Settings
import scorex.transaction.{BlockStorage, History, State, TransactionModule}
import scorex.utils.ScorexLogging
import scorex.wallet.Wallet
import spray.can.Http

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.runtime.universe.Type


trait Application extends ScorexLogging {
  val applicationName: String

  //settings
  implicit val settings: Settings

  //modules
  implicit val consensusModule: ConsensusModule[_]
  implicit val transactionModule: TransactionModule[_]

  //api
  val apiRoutes: Seq[ApiRoute]
  val apiTypes: Seq[Type]

  protected implicit lazy val actorSystem = ActorSystem("lagonaki")
  lazy val apiActor = actorSystem.actorOf(Props(classOf[CompositeHttpServiceActor], apiTypes, apiRoutes), "api")

  protected val additionalMessageSpecs: Seq[MessageSpec[_]]

  lazy val basicMessagesSpecsRepo = new BasicMessagesRepo()

  //p2p
  lazy val messagesHandler: MessageHandler = MessageHandler(basicMessagesSpecsRepo.specs ++ additionalMessageSpecs)

  lazy val peerManager = new PeerManager(settings)

  lazy val networkController = actorSystem.actorOf(Props(classOf[NetworkController], this))
  lazy val blockGenerator = actorSystem.actorOf(Props(classOf[BlockGenerator], this))

  //wallet
  private lazy val walletFileOpt = settings.walletDirOpt.map(walletDir => new java.io.File(walletDir, "wallet.s.dat"))
  implicit lazy val wallet = new Wallet(walletFileOpt, settings.walletPassword, settings.walletSeed.get)

  //interface to append log and state
  val blockStorage: BlockStorage

  lazy val history: History = blockStorage.history
  lazy val state: State = blockStorage.state

  lazy val historySynchronizer = actorSystem.actorOf(Props(classOf[HistorySynchronizer], this))

  def run() {
    checkGenesis()

    IO(Http) ! Http.Bind(apiActor, interface = "0.0.0.0", port = settings.rpcPort)

    historySynchronizer ! Unit
    actorSystem.actorOf(Props(classOf[PeerSynchronizer], this))

    //CLOSE ON UNEXPECTED SHUTDOWN
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping message processor")
    networkController ! NetworkController.ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate().onComplete { _ =>
      //CLOSE WALLET
      log.info("Closing wallet")
      wallet.close()

      //FORCE CLOSE
      log.info("Exiting from the app...")
      System.exit(0)
    }
  }

  def checkGenesis(): Unit = {
    if (transactionModule.blockStorage.history.isEmpty) {
      transactionModule.blockStorage.appendBlock(Block.genesis())
      log.info("Genesis block has been added to the state")
    }
  }.ensuring(transactionModule.blockStorage.history.height() >= 1)
}
