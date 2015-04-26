package tichu.ordinarynode

import akka.actor._
import tichu.ClientMessage._
import tichu.SuperNodeMessage.{Invite, Join, Ready}
import tichu.ordinarynode.InternalMessage._

import scala.collection.mutable.ArrayBuffer

object InternalMessage {

  case class Shutdown(reason: String)

  case class Subscribe(actor: ActorRef)

  case object Prompt

  case class ShowCards(cards: Array[CardInfo])

  case class SpecifyHand(expectedType: Array[Int])

  case class ReceiveToken(var ttl: Int, var cumulative_hand: Array[Array[CardInfo]]) extends Serializable


}

object CardsType{

  case class HandInfo(num: Int, handtype: Int) extends Serializable

  case class Cards(cards: Array[CardInfo]) extends Serializable
}

class OrdinaryNode(name: String) extends Actor with ActorLogging {
  val subscribers = collection.mutable.MutableList[ActorRef]()
  val NUM_OF_PLAYERS = 4
  val NUM_OF_CARDS = 52
  /**
   * Join a supernode and identify yourself with it.
   * @param hostname resolvable address of the supernode, must exactly match the config of the supernode
   * @param port optional port address, defaults to 2553
   */
  def join(hostname: String, port: String = "2553"): Unit = {
    val remote = context.actorSelection(s"akka.tcp://RemoteSystem@$hostname:$port/user/SuperNode")
    remote ! Identify(hostname)
  }

  def receive = connecting orElse common

  /**
   * Defines common messages that the node can receive regardless of state.
   */
  def common: Receive = {
    case Shutdown(reason) => context.stop(self)
    case Subscribe(actor) => subscribers += actor
  }

  /**
   * Messages for the node while in the connecting phase. It listens to two messages:
   * * Join, the command received from the client (e.g. console) telling the node to contact a supernode
   * * ActorIdentity, the response from a supernode on sucessful connection. Contains the ActorRef we need to store.
   *
   * On successful connection we also send a join message to the supernode, which can retrieve our ActorRef through sender().
   * We then also change our state to 'idle' and listen to a new set of messages.
   */

  def connecting: Receive = {
    case Join(hostname) => join(hostname) /* This is the command we receive from the client (e.g. console) */
    case ActorIdentity(host: String, Some(actorRef)) => /* This is the response to the Identify message. It contains the reference to the supernode. */
      context.become(idle(actorRef) orElse common) /* We are now connected, so we change our state to 'idle' */
      actorRef ! Join(name) /* Necessary so that the supernode also has our reference */
      subscribers.foreach(_ ! Prompt) /* Notify the client that we are ready (e.g. console) */
    case ActorIdentity(hostname, None) => log.error("Could not connect to {}", hostname) /* Exception handler when our identify message was not received */
  }

  def idle(superNode: ActorRef): Receive = {
    case SearchingMatch() =>
      superNode ! SearchingMatch()
      context.become(searching(superNode) orElse common)
  }

  def searching(superNode: ActorRef): Receive = {
    case Invite(players) =>
      context.become(matched(superNode) orElse common)
      subscribers.foreach(_ ! Invite(players))
  }

  var receivedToken = new SendToken(NUM_OF_PLAYERS - 1, Array[Array[CardInfo]]())

  def matched(superNode: ActorRef): Receive = {
    case Accept() => superNode ! Accept()
    case Ready(players) => log.info("match with {}", players)
      electLeader(players)
    /*
      receive the initial set of cards from leader
     */
    case DistributeHandCards(cards) => {
      myCards = cards
      subscribers.foreach(_ ! ShowCards(myCards))
      if(findFirstPlayer(cards))
        sortedPlayers(0) ! PlayFirst(self)
    }
    case PlayFirst(sender) =>
      log.debug("First Player: {}", sender)
      sender ! GameStart()
      log.debug("Playing!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

    case GameStart() => log.debug("Phase start!")
      subscribers.foreach(_ ! SpecifyHand(Array(0,0)))

    case SendCards(cards) => log.debug("Phase end!")
      if(cards.length == 0)
        nextActorRef ! SendToken(receivedToken.ttl - 1, receivedToken.cumulative_hand)
      else {
        val new_cumulative_hand = receivedToken.cumulative_hand :+ cards
        nextActorRef ! SendToken(receivedToken.ttl, new_cumulative_hand)
      }

    /*
    receive a token from another player
   */
    case SendToken(ttl, cumulative_hand) => {
      if(ttl == 0)
        receivedToken = new SendToken(NUM_OF_PLAYERS - 1, Array[Array[CardInfo]]())
      else
        receivedToken = new SendToken(ttl, cumulative_hand)
      subscribers.foreach(_ ! ReceiveToken(ttl, cumulative_hand))

    }

  }

  var isLeader = false
  var nextActorRef: ActorRef = _
  val handType = Array(
    "anytype",
    "a single card",
    "a pair",
    "a triple",
    "a row",
    "a full house",
    "a run of pairs",
    "a bomb(four of a kind)",
    "a bomb(straignt flush)"
  )
  val colorType = Array(
    "Jack", "Sword", "Pagoda", "Star"
  )

  var myCards = new Array[CardInfo](13)
  var sortedPlayers: List[ActorRef] = _
  def electLeader(players: Seq[ActorRef]) {
    sortedPlayers = players.toList.sortWith(_.hashCode < _.hashCode) //sort the four ActorRef by hashCode
    if (self.hashCode == sortedPlayers(0).hashCode())
      isLeader = true //check whether the ON is leader
    var next = 0
    for (i <- 0 to 3)
      if (sortedPlayers(i).hashCode() == self.hashCode)
        next = (i + 1) % NUM_OF_PLAYERS
    nextActorRef = sortedPlayers(next) //find the next one who should receive token ring
    log.debug("localActor: {}", self)
    log.debug("isLeader: {}", isLeader)
    log.debug("nextActor: {}", nextActorRef)

    if (isLeader) {
      distributeCards()
    }
  }

  def distributeCards() = {
    //if the ON is leader, shuffle the cards and distribute cards
    val allCards = shuffleCards(getAFullSetOfCards())

    for (i <- 0 until NUM_OF_PLAYERS) {
      val cards = getNthUserCards(allCards, i)
      sortedPlayers(i) ! DistributeHandCards(cards)
    }

  }

  /**
   * * this function is used by the game's initiator. the initiator will create a full set of cards by this function
   * @return
   */
  def getAFullSetOfCards(): Array[CardInfo] = {
    //initialize deck
    var hand = new ArrayBuffer[CardInfo]()
    for (i <- 0 to 12) {
      // different number
      for (j <- 0 to 3) {
        // different color
        hand += new CardInfo(i, j)
      }
    }
    val ret = hand.toArray
    return ret
  }

  /**
   * this function is used by the game's initiator. the initiator will shuffle the newly created cards by this function
   * it takes a set of cards, returns a fully shuffled set of cards
   * @param cards
   * @return
   */

  def shuffleCards(cards: Array[CardInfo]): Array[CardInfo] = {
    //shuffle the deck
    val size = cards.length
    val indexBefore = new Array[Int](size)
    for (i <- 0 until size) {
      indexBefore(i) = i
    }

    val indexAfter = util.Random.shuffle(indexBefore.toSeq).toArray

    val shuffled = new Array[CardInfo](size)
    for (i <- 0 until size) {
      shuffled(i) = cards(indexAfter(i))
    }
    return shuffled
  }

  /**
   * This function is used by the leader, which shuffles and distributes cards
   * to each player
   * @param cards
   * @param n
   * @return
   */
  def getNthUserCards(cards: Array[CardInfo], n: Int): Array[CardInfo] = {
    var hand = new ArrayBuffer[CardInfo]()
    for (i <- n until cards.length by 4) {
      hand += cards(i)
    }
    return hand.toArray
  }

  def findFirstPlayer(cards: Array[CardInfo]): Boolean = {
    for (i <- 0 until cards.length){
      if(cards(i).num == 3 && cards(i).color == 3)
        return true
    }
    return false
  }


}
