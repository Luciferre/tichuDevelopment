package tichu


object ClientMessage {

  final case class SearchingMatch()

  final case class Accept()

  final case class CardInfo(num: Int, color: Int) extends Serializable

  final case class HandCards(cards: Array[CardInfo]) extends Serializable

  final case class ShowCards(cards: Array[CardInfo])

  final case class PlayFirst()

  final case class SendToken()
}
