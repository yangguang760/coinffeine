package coinffeine.model.util

case class Cached[A](cached: A, status: CacheStatus) {

  def map[B](f: A => B): Cached[B] = copy(cached = f(cached))

  def flatMap[B](f: A => Cached[B]): Cached[B] =
    if (isFresh) f(cached) else f(cached).staled

  def staled: Cached[A] = if (status.isFresh) copy(status = CacheStatus.Stale) else this

  def isFresh: Boolean = status.isFresh

  def toOption: Option[A] = if (isFresh) Some(cached) else None
}

object Cached {
  def fresh[T](value: T) = Cached(value, CacheStatus.Fresh)

  def stale[T](value: T) = Cached(value, CacheStatus.Stale)
}

sealed trait CacheStatus {
  def isFresh: Boolean
}

object CacheStatus {

  case object Fresh extends CacheStatus {
    override def isFresh = true
  }

  case object Stale extends CacheStatus {
    override def isFresh = false
  }

}
