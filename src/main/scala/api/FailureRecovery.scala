package api

import zio.clock.Clock
import zio.{ IO, URIO, ZIO }

trait FailureRecovery {
  def run[E, A](fn: => IO[E, A]): A
  def onFail[E, A](fn: => IO[E, A]): A
}
object FailureRecovery {
  def runBoth(
    task1: ZIO[Any with Clock, Throwable, Int],
    task1FailRecovery: ZIO[Any with Clock, Throwable, Int],
    task2: ZIO[Any with Clock, Throwable, Int],
    task2FailRecovery: ZIO[Any with Clock, Throwable, Int]
  ): URIO[Any with Clock, Int] = {

    task1.race()
    val task2WithhRecovery = task2.foldM(
      _ => task1.disconnect *> task1FailRecovery,
      x => ZIO.succeed(x)
    )

    val task1WithhRecovery = task1.foldM(
      _ => task2.disconnect *> task2FailRecovery,
      x => ZIO.succeed(x)
    )

    ZIO.collectAllPar(Seq(task1WithhRecovery,task2WithhRecovery)).fold(_ => -1,x => x.sum)

  }
}
