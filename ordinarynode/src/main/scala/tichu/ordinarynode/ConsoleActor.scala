package tichu.ordinarynode

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import tichu.ClientMessage._
import tichu.SuperNodeMessage.{Join, Invite}
import tichu.ordinarynode.InternalMessage._
import tichu.ordinarynode.CardsType.{HandInfo, Cards}

import scala.collection.immutable.HashSet
import scala.io.StdIn
import scala.collection.mutable.ArrayBuffer
import scala.runtime.ScalaRunTime._
import scala.util.Sorting

class ConsoleActor(node: ActorRef) extends Actor with ActorLogging {
  val input = io.Source.stdin.getLines()
  var myCards = new Array[CardInfo](13)
  val handType = Array(
    "anytype",
    "a single card",
    "a pair",
    "a triple",
    "a row",
    "a full house",
    "a run of pairs",
    "a bomb(four of a kind)",
    "a bomb(straignt flush"
  )
  val colorType = Array(
    "Jack", "Sword", "Pagoda", "Star"
  )


  context.watch(node)

  node ! Subscribe(self)

  def receive = {
    case Prompt => prompt()
    case Terminated => quit()
    case Invite(players) => matchInvite(players)
    case ShowCards(cards) => showCards(cards)
    case SpecifyHand(array) => specifyHand(array)
    case ReceiveToken(ttl, cumulative_hand) => receiveToken(ttl, cumulative_hand)
  }

  def prompt() = {
    print("tichu$ ")
    val HelpCmd = "help ([A-Za-z0-9]*)".r
    val JoinCmd = "join ([^\\s]*)".r

    val command = input.next()
    command.trim() match {
      case "quit" =>
        context.unwatch(node)
        node ! Shutdown("User request")
        context.stop(self)
      case "search" => node ! SearchingMatch()
      case HelpCmd(commandName) => help(commandName)
      case JoinCmd(hostname) => node ! Join(hostname)
      case _ => help(null)
    }
  }

  def matchInvite(players: Seq[String]) = {
    println()
    println("A match has been found with the following players: ")
    players foreach println
    print("Do you accept? (Y/n): ")
    val answer = input.next().trim().toLowerCase
    if (answer.equals("n")) {
      // TODO decline
    } else {
      node ! Accept()
    }
  }

  def help(command: String) = {
    if (command == null) {
      println(
        """The following commands are available:
          |join <hostname>
          |search
          |help <command name>
          |quit
        """.stripMargin)
    } else {
      command.toLowerCase.trim match {
        case "quit" => println( """Shuts the local node done and terminates the client.""")
        case _ => println( """Unknown command. Type 'help' for a list of commands.""")
      }
    }

    self ! Prompt
  }

  def quit() = {
    println()
    println("Local node terminated. Shutting down...")
    context.stop(self)
  }

  def showCards(cards: Array[CardInfo]): Unit = {
    println(">> You have " + cards.length + " cards available")
    for (i <- 0 until cards.length) {
      println("Card #" + i + "; Number is " + cards(i).num + "\t; Color is " + colorType(cards(i).color)) //+ colorType(cards(i).color))
    }
  }

  /**
   * show all the cards to user, let user to specify the hand
   * deduce the selected cards from myCards
   * return the indexes of the selected cards
   * @param expectedType
   * @return
   */
  def specifyHand(expectedType: Array[Int]) = {
    println("The expected card type is: " + handType(expectedType(1)))
    println("You have the following available cards: ")
    showCards(myCards)
    if (expectedType(0) == 0) {
      println("You can pick any card")
    } else {
      println("The expected card number is " + expectedType(0))
    }
    println("Please specify card")
    var hand = new ArrayBuffer[CardInfo]()
    var indexes = new ArrayBuffer[Int]()
    // return any type of hands, as long as it is legal
    var notReady = true // loop control
    var isPass = false // loop control
    while (notReady) {
      hand.clear()
      indexes.clear()
      // wait for a hand of cards
      var cond = true;
      while (cond) {
        val ipt = StdIn.readLine()
        if (ipt == "done") {
          cond = false
        } else if (ipt == "pass") {
          cond = false
          isPass = true
        } else if (ipt == "help") {
          println(
            """The following commands are available:
              |Please input the number of card and press Enter
              |done
              |pass
            """.stripMargin)
        } else {
          //val tk = ipt.split(",")
          //var card = new CardInfo(tk(0).toInt, tk(1).toInt)
          hand += myCards(ipt.toInt)
          indexes += (ipt.toInt)
        }
      }
      // check if the specified hand meets demands
      val check = getHandType(hand.toArray)
      // if there is no limit of card type, then any hand is OK
      // if there is a limit of card type, you must specify the expected type
      if ((expectedType(1) == 0 && check(1) != 0) ||
        (check(1) != 0 && check(1) == expectedType(1) && check(0) > expectedType(0))) {
        println("I think this hand is valid")
        notReady = false
      } else {
        println("This hand is not valid, do you want to go on selecting[yes] or not[pass]?")
        val ipt = StdIn.readLine()
        if (ipt == "pass") {
          // jump out of this loop
          notReady = false
        }
      }
    }
    val toSend = new ArrayBuffer[CardInfo]()
    // if the user decided to pass, return null. else return the cards specified
    if (isPass) node ! SendCards(toSend.toArray)
    else {
      // put the indexes into a hashset
      var hs = new HashSet[Int]()
      val selectedIndexes = indexes.toArray
      for (i <- 0 until selectedIndexes.length) {
        hs += selectedIndexes(i)
      }
      // classfy all my cards.
      val remaining = new ArrayBuffer[CardInfo]()
      for (i <- 0 until myCards.length) {
        if (hs contains i) {
          toSend += myCards(i)
        } else {
          remaining += myCards(i)
        }
      }
      myCards = remaining.toArray
      println("I have these cards remaining:")
      showCards(myCards)
      // convert to array, sort, and return
      node ! SendCards(toSend.toArray)
    }
  }

  /**
   * given a hand of cards, judge its type
    return an array(flag_num, type)
    for example, if we have 77744, a full house, the return value is (7, 5)
    if we have 444, the return value is (4, 3)
    below is the code for different types:
    8: a bomb(straight flush)
    7: a bomb(four of a kind)
    6: a run of pairs
    5: a full house
    4: a row
    3: a triple
    2: a pair
    1: a single card
    0: unrecognizable
   * @param hand
   * @return
   */
  def getHandType(hand: Array[CardInfo]): Array[Int] = {
    // sort the cards
    val sorted = SortCards(hand)
    println(stringOf(sorted))
    val len = array_length(sorted)
    if (len == 6) {
      // A run of pairs of adjoining values - 9,9,10,10,J,J
      if (sorted(0).num == sorted(1).num &&
        sorted(2).num == sorted(3).num &&
        sorted(4).num == sorted(5).num &&
        sorted(1).num != sorted(2).num &&
        sorted(3).num != sorted(4).num) {
        return Array(sorted(5).num, 6)
      } else {
        return Array(0, 0)
      }
    } else if (len == 5) {
      // a bomb(straight flush)
      if ((sorted(0).num + 1) == sorted(1).num &&
        (sorted(1).num + 1) == sorted(2).num &&
        (sorted(2).num + 1) == sorted(3).num &&
        (sorted(3).num + 1) == sorted(4).num &&
        sorted(0).color == sorted(1).color &&
        sorted(1).color == sorted(2).color &&
        sorted(2).color == sorted(3).color &&
        sorted(3).color == sorted(4).color) {
        return Array(sorted(4).num, 8)
      }
      // A row (also known as a straight or run) of at least five cards (the Ace can be used in a run but only as a high card such as 10JQKA - A2345 is not a legal play) - 4,5,6,7,8
      else if ((sorted(0).num + 1) == sorted(1).num &&
        (sorted(1).num + 1) == sorted(2).num &&
        (sorted(2).num + 1) == sorted(3).num &&
        (sorted(3).num + 1) == sorted(4).num) {
        return Array(sorted(4).num, 4)
      }
      // A full house (triple and pair) - 5,5,5,9,9
      else if ((sorted(0).num == sorted(1).num && //AAABB
        sorted(1).num == sorted(2).num &&
        sorted(3).num == sorted(4).num &&
        sorted(2).num != sorted(3).num)) {
        return Array(sorted(0).num, 5)
      } else if (sorted(0).num == sorted(1).num && //AABBB
        sorted(2).num == sorted(3).num &&
        sorted(3).num == sorted(4).num &&
        sorted(1).num != sorted(2).num) {
        return Array(sorted(4).num, 5)
      } else {
        return Array(0, 0)
      }
    } else if (len == 4) {
      if (sorted(0).num == sorted(3).num) {
        return Array(sorted(0).num, 7)
      } else {
        return Array(0, 0)
      }
    } else if (len == 3) {
      //A triple (three cards of the same value) - 2,2,2
      if (sorted(0).num == sorted(1).num &&
        sorted(1).num == sorted(2).num) {
        return Array(sorted(0).num, 3)
      } else {
        return Array(0, 0)
      }
    } else if (len == 2) {
      //A pair (two cards of the same value) - 8,8
      if (sorted(0).num == sorted(1).num) {
        return Array(sorted(0).num, 2)
      } else {
        return Array(0, 0)
      }
    } else if (len == 1) {
      // A single card - 4
      return Array(sorted(0).num, 1)
    } else {
      return Array(0, 0)
    }
  }

  /**
   * sort the hand of cards by the card num. return an array of cardinfo
   * @param hand
   * @return
   */
  def SortCards(hand: Array[CardInfo]): Array[CardInfo] = {
    /*
     compare two hands of cards, return:
     -2 if the two hands are incomparable
     -1 if hand0 < hand1
     0 if hand0 = hand1
     1 if hand0 > hand1
     */
    def compareHand(hand0: HandInfo, hand1: HandInfo): Int = {
      if (hand0.handtype != hand1.handtype) return -2
      else {
        if (hand0.num > hand1.num) return 1
        else if (hand0.num == hand1.num) return 0
        else return -1
      }
    }

    object CardOrdering extends Ordering[CardInfo] {
      def compare(a: CardInfo, b: CardInfo) = a.num compare b.num
    }
    Sorting.quickSort(hand)(CardOrdering)
    return hand
  }

  def receiveToken(ttl: Int, cumulative_hand: Array[Array[CardInfo]]) = {
    // ttl == 0 means all other players have passed. it is my turn again
    if (ttl == 0) {
      // todo: add score to my current scores, and inform the GUI of this message
      println("GREAT, all your opposites passed your hand, it's your turn again!")
      // wait for user to specify a hand of cards
      // create a token
      specifyHand(Array(0, 0))

    } else {
      // display the token first,
      println("The cumulative hands are: ")
      println(runtime.ScalaRunTime.stringOf(cumulative_hand))

      // the previous hand is the last entry in cumulative hand
      val prev_hand = cumulative_hand(cumulative_hand.length - 1)
      // prompt the user to specify a hand that meet the demand
      specifyHand(getHandType(prev_hand))

    }
  }
}