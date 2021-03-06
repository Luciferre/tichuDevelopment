package tichu

import akka.actor.{ActorPath, ActorRef}
import tichu.ClientMessage.CardInfo

object SuperNodeMessage {

  final case class Join(name: String)

  final case class Invite(players: Seq[String])

  final case class Ready(remotes: Seq[ActorRef])

  final case class PlayerRequest(origin: ActorPath, seqNum: Int, players: Seq[ActorRef])

  final case class AvailablePlayers(request: (ActorPath, Int), players: Seq[ActorRef])

  final case class GameOver()

  final case class MultiCast(cards: Array[CardInfo], players: Seq[ActorRef])

  final case class SNForwardToken(var ttl: Int, var cumulative_hand: Array[Array[CardInfo]]) extends Serializable

}