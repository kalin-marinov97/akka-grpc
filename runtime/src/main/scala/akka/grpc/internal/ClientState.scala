/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.grpc.internal

import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.grpc.GrpcClientSettings
import akka.pattern.Patterns
import akka.stream.{ ActorMaterializer, Materializer }
import cats.implicits._
import io.grpc.ManagedChannel

import scala.annotation.tailrec
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Failure

/**
 * INTERNAL API
 * Used from generated code so can't be private.
 *
 * Client utilities taking care of Channel reconnection and Channel lifecycle in general.
 */
@InternalApi
final class ClientState(
    settings: GrpcClientSettings,
    log: LoggingAdapter,
    channelFactory: GrpcClientSettings => Future[List[Future[InternalChannel]]])(
    implicit mat: Materializer,
    ex: ExecutionContext) {
  def this(settings: GrpcClientSettings, log: LoggingAdapter)(implicit mat: Materializer, ex: ExecutionContext) =
    this(settings, log, s => NettyClientUtils.createChannel(s))

  // usually None, it'll have a value when the underlying InternalChannel is closing or closed.
  private val closing = new AtomicReference[Option[Future[List[Done]]]](None)
  private val closeDemand: Promise[Done] = Promise[Done]()

  private val internalChannelRef =
    new AtomicReference[Option[Future[(List[InternalChannel], Iterator[InternalChannel])]]](Some(create()))
  internalChannelRef.get().getOrElse(Future.failed(new ClientClosedException)).map(Iterator.continually(_))
  internalChannelRef.get().foreach { c =>
    val future = c.flatMap(_._1.traverse(r => r.done))
    recreateOnFailure(future, settings.creationAttempts)
  }

  mat match {
    case m: ActorMaterializer =>
      m.system.whenTerminated.foreach(_ => close())(ex)
    case _ =>
  }

  // used from generated client
  def withChannel[A](f: Future[ManagedChannel] => A): A =
    f {
      internalChannelRef.get().getOrElse(Future.failed(new ClientClosedException)).map(_._2.next.managedChannel)
    }

  def closedCS(): CompletionStage[Done] = closed().toJava
  def closeCS(): CompletionStage[Done] = close().toJava

  def closed(): Future[Done] =
    // while there's no request to close this RestartingClient, it will continue to restart.
    // Once there's demand, the `closeDemand` future will redeem flatMapping with the `closing`
    // future which is a reference to promise of the internalChannel close status.
    closeDemand.future
      .flatMap { _ =>
        // `closeDemand` guards the read access to `closing`
        closing.get().get
      }
      .map(_ => Done.getInstance)

  def close(): Future[Done] = {
    val maybeChannel = internalChannelRef.get()
    (maybeChannel match {
      case Some(channel) =>
        // invoke `close` on the channel and capture the `channel.done` returned
        val done: Future[List[Done]] = channel.flatMap(_._1.traverse(ChannelUtils.close))
        // set the `closing` to the current `channel.done`
        closing.compareAndSet(None, Some(done))
        // notify there's been close demand (see `def closed()` above)
        closeDemand.trySuccess(Done)

        if (internalChannelRef.compareAndSet(maybeChannel, None)) {
          done
        } else {
          // when internalChannelRef was not maybeChannel
          if (internalChannelRef.get != null) {
            // client has had an exception and been re-created, need to shutdown the new one
            close().map(List(_))
          } else {
            // or a competing thread already set `internalChannelRef` to None and CAS failed.
            done
          }
        }
      case _ =>
        // set the `closing` to immediate success
        val done = Future.successful(List(Done))
        closing.compareAndSet(None, Some(done))
        // notify there's been close demand (see `def closed()` above)
        closeDemand.trySuccess(Done)
        done
    }).map(_ => Done.getInstance)
  }

  private def create(): Future[(List[InternalChannel], Iterator[InternalChannel])] = {
    import cats.implicits._
    val future: Future[List[InternalChannel]] = channelFactory(settings).flatMap {
      _.traverse { f =>
        Patterns.retry(
          () => f,
          settings.creationAttempts,
          settings.creationDelay,
          // TODO #733 remove cast once we update Akka
          mat.asInstanceOf[ActorMaterializer].system.scheduler,
          mat.asInstanceOf[ActorMaterializer].system.dispatcher)
      }
    }
    future.map(r => (r, Iterator.continually(r).flatten))
  }

  private def recreateOnFailure(done: Future[List[Done]], creationsLeft: Int): Unit =
    done.onComplete {
      case Failure(e) =>
        if (creationsLeft <= 0) {
          // Error does not need to be explicitly propagated here
          // since it's in the internalChannelRef already
          log.warning(s"Client error [${e.getMessage}], not recreating client")
          close()
        } else if (settings.creationDelay.length < 1) {
          if (!closeDemand.isCompleted) {
            log.warning(s"Client error [${e.getMessage}], recreating client")
            recreate(creationsLeft - 1)
          }
        } else {
          log.warning(s"Client error [${e.getMessage}], recreating client after ${settings.creationDelay}")

          Patterns.after(
            settings.creationDelay,
            // TODO #733 remove cast once we update Akka
            mat.asInstanceOf[ActorMaterializer].system.scheduler,
            mat.asInstanceOf[ActorMaterializer].system.dispatcher,
            () =>
              Future {
                log.info("Recreating channel now")
                if (!closeDemand.isCompleted) {
                  recreate(creationsLeft - 1)
                }
              })
        }
      case _ =>
        log.info("Client closed")
      // completed successfully, nothing else to do (except perhaps log?)
    }

  private def recreate(creationsLeft: Int): Unit = {
    val old = internalChannelRef.get()
    if (old.isDefined) {
      val newInternalChannel = create()
      recreateOnFailure(newInternalChannel.flatMap(_._1.traverse(_.done)), creationsLeft)
      // Only one client is alive at a time. However a close() could have happened between the get() and this set
      if (!internalChannelRef.compareAndSet(old, Some(newInternalChannel))) {
        // close the newly created client we've been shutdown
        newInternalChannel.flatMap(_._1.traverse(ChannelUtils.close(_)))
      }
    }
  }
}

/**
 * INTERNAL API
 * Used from generated code so can't be private.
 *
 * Thrown if a withChannel call is called after closing the internal channel
 */
@InternalApi
final class ClientClosedException() extends RuntimeException("withChannel called after close()")
