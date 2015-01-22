/*
 * This software is licensed under the GNU Affero General Public License, quoted below.
 *
 * This file is a part of PowerAPI.
 *
 * Copyright (C) 2011-2014 Inria, University of Lille 1.
 *
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI.
 *
 * If not, please consult http://www.gnu.org/licenses/agpl-3.0.html.
 */
package org.powerapi.module.libpfm

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.powerapi.UnitTest

import scala.concurrent.Future

class LibpfmCoreSensorSuite(system: ActorSystem) extends UnitTest(system) {
  import akka.actor.Props
  import akka.util.Timeout
  import org.powerapi.core.MessageBus
  import scala.concurrent.duration.DurationDouble

  def this() = this(ActorSystem("LibpfmCoreSensorSuite"))

  val timeout = Timeout(1.seconds)

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
  }

  trait Bus {
    val eventBus = new MessageBus
  }

  "A LibpfmCoreSensor" should "aggregate the performance counters" ignore new Bus {
    import akka.actor.Terminated
    import akka.pattern.gracefulStop
    import akka.testkit.{TestActorRef, TestProbe}
    import java.util.{BitSet, UUID}
    import org.powerapi.core.All
    import org.powerapi.core.ClockChannel.ClockTick
    import org.powerapi.core.MonitorChannel.MonitorTick
    import org.powerapi.module.SensorChannel.monitorAllStopped
    import PerformanceCounterChannel.{PCReport, subscribePCReport}
    import scala.collection.mutable.ArrayBuffer
    import scala.concurrent.Await
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.sys.process.stringSeqToProcess

    val configuration = new BitSet()
    configuration.set(0)
    configuration.set(1)
    val muid1 = UUID.randomUUID()
    val muid2 = UUID.randomUUID()
    val events = Array("CPU_CLK_UNHALTED:THREAD_P", "instructions")
    val cores = Map(0 -> List(0, 1))
    val buffer = ArrayBuffer[PCReport]()

    val basepath = getClass.getResource("/").getPath
    val pid1 = Seq("bash", s"${basepath}test-pc.bash").lineStream(0).trim.toInt
    Seq("taskset", "-cp", "0" ,s"$pid1").!
    val pid2 = Seq("bash", s"${basepath}test-pc.bash").lineStream(0).trim.toInt
    Seq("taskset", "-cp", "1" ,s"$pid2").!

    LibpfmHelper.init()

    val reaper = TestProbe()(system)
    val sensor = TestActorRef(Props(classOf[LibpfmCoreSensor], eventBus, timeout, configuration, events, cores), "sensor1")(system)

    subscribePCReport(eventBus)(testActor)

    Seq("kill", "-SIGCONT", s"$pid1").!!
    Seq("kill", "-SIGCONT", s"$pid2").!!
    sensor ! MonitorTick("monitor", muid1, All, ClockTick("clock", 1.second))
    sensor ! MonitorTick("monitor", muid2, All, ClockTick("clock", 1.second))
    Thread.sleep(1000)
    sensor ! MonitorTick("monitor", muid1, All, ClockTick("clock", 1.second))
    buffer += expectMsgClass(classOf[PCReport])
    sensor ! MonitorTick("monitor", muid2, All, ClockTick("clock", 1.second))
    buffer += expectMsgClass(classOf[PCReport])
    Thread.sleep(1000)
    sensor ! MonitorTick("monitor", muid1, All, ClockTick("clock", 1.second))
    buffer += expectMsgClass(classOf[PCReport])
    sensor ! MonitorTick("monitor", muid2, All, ClockTick("clock", 1.second))
    buffer += expectMsgClass(classOf[PCReport])
    Seq("kill", "-SIGKILL", s"$pid1").!!
    Seq("kill", "-SIGKILL", s"$pid2").!!

    buffer.foreach(msg => {
      msg match {
        case PCReport(_, _, target, wrappers, _) => {
          target should equal(All)
          wrappers.size should equal(cores.size * events.size)
          events.foreach(event => wrappers.count(_.event == event) should equal(cores.size))
          wrappers.foreach(wrapper => wrapper.values.size should equal(events.size))
        }
      }

      for(wrapper <- msg.wrappers) {
        Future.sequence(wrapper.values) onSuccess {
          case coreValues: List[Long] => {
            val aggValue = coreValues.foldLeft(0l)((acc, value) => acc + value)
            aggValue should be >= 0l
            println(s"muid: ${msg.muid}; core: ${wrapper.core}; event: ${wrapper.event}; value: $aggValue")
          }
        }
      }
    })

    val children = sensor.children.toArray.clone()
    children.foreach(child => reaper watch child)

    monitorAllStopped()(eventBus)
    for(_ <- 0 until children.size) {
      reaper.expectMsgClass(classOf[Terminated])
    }

    Await.result(gracefulStop(sensor, 1.seconds), 1.seconds)
    LibpfmHelper.deinit()
  }
}
