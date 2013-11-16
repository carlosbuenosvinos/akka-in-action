package com.manning.aa
package words

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.routing._
import akka.routing._


object JobMaster {
  def props = Props[JobMaster]

  case class StartJob(name: String, text: List[String])
  case class Enlist(worker:ActorRef)

  case object NextTask
  case class TaskResult(map: Map[String, Int])

  case object Start
  case object MergeResults
}

class JobMaster extends Actor
                   with ActorLogging
                   with BroadcastWork {
  import JobReceptionist.WordCount
  import JobMaster._
  import JobWorker._
  import context._

  var textParts = Vector[List[String]]()
  var intermediateResult = Vector[Map[String, Int]]()
  var workGiven = 0
  var workReceived = 0
  var workers = Set[ActorRef]()

  val router = createRouter

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = idle

  def idle: Receive = {
    case StartJob(jobName, text) =>
      textParts = text.grouped(10).toVector
      val cancellable = context.system.scheduler.schedule(0 millis, 1000 millis, router, Work(jobName, self))

      become(working(jobName, sender, cancellable))
  }

  def working(jobName:String,
              receptionist:ActorRef,
              cancellable:Cancellable): Receive = {
    case Enlist(worker) =>
      watch(worker)
      workers  = workers + worker
    case NextTask =>
      if(textParts.isEmpty) {
        sender ! WorkLoadDepleted
      } else {
        sender ! Task(textParts.head, self)
        workGiven = workGiven + 1
        textParts = textParts.tail
      }

    case TaskResult(countMap) =>
      intermediateResult = intermediateResult :+ countMap
      workReceived = workReceived + 1

      if(textParts.isEmpty && workGiven == workReceived) {
        cancellable.cancel()
        become(finishing(jobName, receptionist, workers))
        self ! MergeResults
      }

    case Terminated(worker) =>
      log.info(s"Worker $worker got terminated. Cancelling job $jobName.")
      stop(self)
  }

  def finishing(jobName: String,
                receptionist: ActorRef,
                workers: Set[ActorRef]): Receive = {
    case MergeResults =>
      val mergedMap = intermediateResult.foldLeft(Map[String, Int]()){ (el, acc) =>
        el.map { case (word, count) =>
          acc.get(word).map( accCount => (word ->  (accCount + count))).getOrElse(word -> count)
        } ++ (acc -- el.keys)
      }
      workers.foreach(stop(_))
      receptionist ! WordCount(jobName, mergedMap)

    case Terminated(worker) =>
      log.info(s"Job $jobName is finishing. Worker ${worker.path.name} is stopped.")
  }
}


trait BroadcastWork { this: Actor =>
  def createRouter: ActorRef = {
    context.actorOf(
      ClusterRouterPool(BroadcastPool(10), ClusterRouterPoolSettings(
        totalInstances = 100, maxInstancesPerNode = 20,
        allowLocalRoutees = false, useRole = None)).props(Props[JobWorker]),
      name = "worker-router")
  }
}
