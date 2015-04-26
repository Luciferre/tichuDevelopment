package tichu

import akka.actor.ActorRef


object ClientMessage {

  final case class SearchingMatch()

  final case class Accept()

  final case class CardInfo(num: Int, color: Int) extends Serializable

  final case class DistributeHandCards(cards: Array[CardInfo]) extends Serializable

  final case class PlayFirst(sender: ActorRef)

  final case class SendCards(cards: Array[CardInfo])

  final case class GameStart()

  final case class SendToken(var ttl: Int, var cumulative_hand: Array[Array[CardInfo]]) extends Serializable

  final case class ForwardGameOver(actor: ActorRef)

  final case class ForwardToken(var ttl: Int, var cumulative_hand: Array[Array[CardInfo]], actor:ActorRef) extends Serializable

}
