/*
 * Copyright 2022-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http.netty.client

import java.util.concurrent.TimeUnit

import zio._
import zio.internal.Platform

import java.util.concurrent.ConcurrentHashMap
import java.util.{Collections, Map => JMap, Set => JSet, WeakHashMap}

object ZKeyedPoolTest {

  def currentMs() = Clock.currentTime(TimeUnit.MILLISECONDS)

  /**
   * Makes a new pool of the specified fixed size. The pool is returned in a
   * `Scope`, which governs the lifetime of the pool. When the pool is shutdown
   * because the `Scope` is closed, the individual items allocated by the pool
   * will be released in some unspecified order.
   */
  def make[Key, Env: EnvironmentTag, Err, Item](get: Key => ZIO[Env, Err, Item], size: => Int)(implicit
    trace: Trace,
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] =
    make(get, _ => size)

  /**
   * Makes a new pool of the specified fixed size. The pool is returned in a
   * `Scope`, which governs the lifetime of the pool. When the pool is shutdown
   * because the `Scope` is closed, the individual items allocated by the pool
   * will be released in some unspecified order.
   *
   * The size of the underlying pools can be configured per key.
   */
  def make[Key, Env: EnvironmentTag, Err, Item](get: Key => ZIO[Env, Err, Item], size: Key => Int)(implicit
    trace: Trace,
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] =
    makeWith(get, (key: Key) => { val s = size(key); s to s })(_ => None)

  /**
   * Makes a new pool with the specified minimum and maximum sizes and time to
   * live before a pool whose excess items are not being used will be shrunk
   * down to the minimum size. The pool is returned in a `Scope`, which governs
   * the lifetime of the pool. When the pool is shutdown because the `Scope` is
   * used, the individual items allocated by the pool will be released in some
   * unspecified order.
   */
  def make[Key, Env: EnvironmentTag, Err, Item](
    get: Key => ZIO[Env, Err, Item],
    range: => Range,
    timeToLive: => Duration,
  )(implicit
    trace: Trace,
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] =
    make(get, _ => range, _ => timeToLive)

  /**
   * Makes a new pool with the specified minimum and maximum sizes and time to
   * live before a pool whose excess items are not being used will be shrunk
   * down to the minimum size. The pool is returned in a `Scope`, which governs
   * the lifetime of the pool. When the pool is shutdown because the `Scope` is
   * used, the individual items allocated by the pool will be released in some
   * unspecified order.
   *
   * The size of the underlying pools can be configured per key.
   */
  def make[Key, Env: EnvironmentTag, Err, Item](
    get: Key => ZIO[Env, Err, Item],
    range: Key => Range,
    timeToLive: Key => Duration,
  )(implicit
    trace: Trace,
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] =
    makeWith(get, range)((key: Key) => Some(timeToLive(key)))

  private def makeWith[Key, Env: EnvironmentTag, Err, Item](get: Key => ZIO[Env, Err, Item], range: Key => Range)(
    ttl: Key => Option[Duration],
  )(implicit
    trace: Trace,
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] =
    for {
      environment <- ZIO.environment[Env]
      fiberId     <- ZIO.fiberId
      map         <- ZIO.succeed(Platform.newConcurrentMap[Key, MapValue[Err, Item]]()(Unsafe.unsafe))
      scope       <- ZIO.scope
      getPool = (key: Key) => ZIO.succeed(map.get(key))
      getOrCreatePool = (key: Key) =>
        ZIO.suspendSucceed {
          // TODO: do we even need this check?
          var value = map.get(key)
          if (value eq null) {
            ZIO.uninterruptibleMask { restore =>
              val promise  = Promise.unsafe.make[Nothing, ZPool[Err, Item]](fiberId)(Unsafe.unsafe)
              value = MapValue.Pending(promise)
              val previous = map.putIfAbsent(key, value)
              if (previous eq null) {
                restore(
                  scope
                    .extend(
                      for {
                        ms1 <- currentMs
                        out <- ZPoolTest
                          .make(
                            get(key).provideEnvironment(environment),
                            range(key),
                            // Range(1,1),  // nocheckin
                            ttl(key).getOrElse(Duration.Infinity),
                          )
                        ms2 <- currentMs
                        _   <- Console.printLine(s"ZPoolTest.make: ${ms2 - ms1}ms").orDie
                      } yield out,
                    ),
                ).foldCauseZIO(
                  cause => {
                    map.remove(key, value)
                    promise.failCause(cause) *> ZIO.failCause(cause)
                  },
                  pool => {
                    map.put(key, MapValue.Complete(pool))
                    promise.succeed(pool).as(pool)
                  },
                )
              } else {
                previous match {
                  case MapValue.Complete(pool)   => ZIO.succeed(pool)
                  case MapValue.Pending(promise) => restore(promise.await)
                }
              }
            }
          } else {
            value match {
              case MapValue.Complete(pool)   => ZIO.succeed(pool)
              case MapValue.Pending(promise) => promise.await
            }
          }
        }
      activePools     = ZIO.suspendSucceed {
        ZIO.foreach(Chunk.fromJavaIterator(map.values.iterator)) {
          case MapValue.Complete(pool)   => ZIO.succeed(pool)
          case MapValue.Pending(promise) => promise.await
        }
      }
    } yield DefaultKeyedPool(getOrCreatePool, activePools)

  private final case class DefaultKeyedPool[Err, Key, Item](
    getOrCreatePool: Key => ZIO[Any, Nothing, ZPool[Err, Item]],
    activePools: ZIO[Any, Nothing, Chunk[ZPool[Err, Item]]],
    // getPool: Key => ZIO[Any, Nothing, ZPool[Err, Item]],
  ) extends ZKeyedPool[Err, Key, Item] {

    override def get(key: Key)(implicit trace: Trace): ZIO[Scope, Err, Item] = for {
      ms1    <- currentMs
      result <- getOrCreatePool(key).timed
      (time, pool) = result
      _ <- Console.printLine(s"ZKeyedPoolTest.getOrCreatePool: ${time.toMillis}ms").orDie

      out <- pool.get
      ms2 <- currentMs
      _   <- Console.printLine(s"ZKeyedPoolTest.DefaultKeyedPool.get: ${ms2 - ms1}ms").orDie
    } yield out

    def invalidate(item: Item)(implicit trace: Trace): UIO[Unit] =
      activePools.flatMap(ZIO.foreachDiscard(_)(_.invalidate(item)))
  }

  private sealed trait MapValue[Err, Item]

  private object MapValue {
    final case class Complete[Err, Item](pool: ZPool[Err, Item])                     extends MapValue[Err, Item]
    final case class Pending[Err, Item](promise: Promise[Nothing, ZPool[Err, Item]]) extends MapValue[Err, Item]
  }
}
