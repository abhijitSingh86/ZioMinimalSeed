package api

import zio.ZIO
import zio.duration._
import zio.test.Assertion.equalTo
import zio.test._
import zio.test.environment.TestClock


object FailureRecoverySpec  extends DefaultRunnableSpec {
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("Failure Recovery spec")(
    testM("both task successfully ran"){

      def task1  = ZIO.succeed(1).delay(1.seconds)
      def task2  = ZIO.succeed(1).delay(1.seconds)

      for {
        resF <- FailureRecovery.runBoth(task1,ZIO.succeed(1), task2,ZIO.succeed(1)).fork
      _ <- TestClock.adjust(2.seconds)
      res <- resF.join
      }yield assert(res)(equalTo(2))
    },
    testM("Failure in task 2"){

      def task1  = ZIO.succeed(1).delay(1.seconds)
      def task1FailureRollover =  ZIO.succeed(5)
      def task2FailureRollover =  ZIO.succeed(9)
      def task2  = ZIO.fail(new Exception("ex")).delay(2.seconds)

      for {
        resF <- FailureRecovery.runBoth(task1,task1FailureRollover, task2, task2FailureRollover).fork
        _ <- TestClock.adjust(1.seconds)
        _ <- TestClock.adjust(2.seconds)
        res <- resF.join
      }yield assert(res)(equalTo(6))
    },
    testM("Failure in task1"){

      def task1  = ZIO.fail(new Exception("ex")).delay(1.seconds)
      def task1FailureRollover =  ZIO.succeed(5)
      def task2FailureRollover =  ZIO.succeed(9)
      def task2  = (ZIO.succeed(println("Not even called")) *> ZIO.succeed(1)).delay(2.seconds)

      for {
        resF <- FailureRecovery.runBoth(task1,task1FailureRollover, task2, task2FailureRollover).fork
        _ <- TestClock.adjust(1.seconds)
        _ <- TestClock.adjust(2.seconds)
        res <- resF.join
      }yield assert(res)(equalTo(10))
    }
  )
}
