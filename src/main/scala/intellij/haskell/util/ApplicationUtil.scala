package intellij.haskell.util

import java.util.concurrent.atomic.AtomicReference

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.util.ReadTask.Continuation
import com.intellij.openapi.progress.util.{ProgressIndicatorUtils, ReadTask}
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager}
import com.intellij.openapi.project.{DumbService, Project}
import intellij.haskell.HaskellNotificationGroup
import intellij.haskell.external.component.{NoInfo, ReadActionTimeout}

import scala.concurrent.duration._

object ApplicationUtil {

  def runReadAction[T](f: => T): T = {
    ApplicationManager.getApplication.runReadAction(ScalaUtil.computable(f))
  }

  final val RunInReadActionTimeout = 5.millis

  def runInReadActionWithWriteActionPriority[A](project: Project, f: => A, readActionDescription: => String, timeout: FiniteDuration = RunInReadActionTimeout): Either[NoInfo, A] = {
    val r = new AtomicReference[A]

    val application = ApplicationManager.getApplication

    if (application.isDispatchThread) {
      Right(f)
    } else {
      def run(): Boolean = {
        ProgressIndicatorUtils.runInReadActionWithWriteActionPriority {
          ScalaUtil.runnable {
            ProgressManager.checkCanceled()
            r.set(f)
          }
        }
      }

      val deadline = timeout.fromNow

      while (!run() && deadline.hasTimeLeft && !project.isDisposed) {
        Thread.sleep(1)
      }

      val result = r.get()
      if (result == null) {
        HaskellNotificationGroup.logInfoEvent(project, s"Timeout in runInReadActionWithWriteActionPriority while $readActionDescription")
        Left(ReadActionTimeout(readActionDescription))
      } else {
        //        HaskellNotificationGroup.logInfoEvent(project, s"runInReadAction $readActionDescription took " + (timeout - deadline.timeLeft).toMillis + "ms")
        Right(result)
      }
    }
  }

  final val ScheduleInReadActionTimeout = 1000.millis

  def scheduleInReadActionWithWriteActionPriority[A](project: Project, f: => A, scheduleInReadActionDescription: => String, timeout: FiniteDuration = ScheduleInReadActionTimeout): Either[NoInfo, A] = {
    val r = new AtomicReference[A]
    val application = ApplicationManager.getApplication

    if (application.isDispatchThread) {
      Right(f)
    } else {
      ProgressIndicatorUtils.scheduleWithWriteActionPriority {
        new ReadTask {

          override def runBackgroundProcess(indicator: ProgressIndicator): Continuation = {
            DumbService.getInstance(project).runReadActionInSmartMode(() => {
              performInReadAction(indicator)
            })
          }

          override def onCanceled(indicator: ProgressIndicator): Unit = {
            ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
          }

          override def computeInReadAction(indicator: ProgressIndicator): Unit = {
            r.set(f)
          }
        }
      }

      val deadline = timeout.fromNow

      while (r.get == null && deadline.hasTimeLeft && !project.isDisposed) {
        Thread.sleep(1)
      }

      val result = r.get()
      if (result == null) {
        HaskellNotificationGroup.logInfoEvent(project, s"Timeout in scheduleInReadActionWithWriteActionPriority while $scheduleInReadActionDescription")
        Left(ReadActionTimeout(scheduleInReadActionDescription))
      } else {
        //        HaskellNotificationGroup.logInfoEvent(project, s"scheduleInReadAction $scheduleInReadActionDescription took " + (timeout - deadline.timeLeft).toMillis + "ms")
        Right(result)
      }
    }
  }
}

