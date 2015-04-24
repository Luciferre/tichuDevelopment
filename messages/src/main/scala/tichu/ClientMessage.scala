package tichu


object ClientMessage {

  final case class SearchingMatch()

  final case class Accept()

  case class CardInfo(num: Int, color: Int) extends Serializable

  final case class HandCards(cards: Array[CardInfo]) extends Serializable

  case class ShowCards(cards: Array[CardInfo])


}
