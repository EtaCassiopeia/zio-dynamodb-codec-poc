package poc

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue => AV}
import zio.blocks.schema.*

import java.util.concurrent.TimeUnit

case class User(
    @Modifier.rename("user_name") name: String,
    age: Int,
    email: String,
    @Modifier.rename("login_count") loginCount: Long,
    bio: Option[String]
) derives Schema

object User:
  val codec: DynamoDBCodec[User] =
    summon[Schema[User]].deriving[DynamoDBCodec](DynamoDBCodecDeriver).derive.asInstanceOf[DynamoDBCodec[User]]

object DynosaurSetup:
  import dynosaur.Schema as DS
  import cats.syntax.all.*

  case class User(name: String, age: Int, email: String, loginCount: Long, bio: Option[String])

  val schema: DS[User] = DS.record[User] { field =>
    (
      field("user_name", _.name),
      field("age", _.age),
      field("email", _.email),
      field("login_count", _.loginCount),
      field.opt("bio", _.bio)
    ).mapN(User.apply)
  }

@State(Scope.Thread)
class BenchState:
  val user = User("Alice", 30, "alice@example.com", 42L, Some("Scala developer"))
  val codec = User.codec
  val encoded: java.util.Map[String, AV] =
    val m = new java.util.HashMap[String, AV](); codec.encode(user, m); m

  val dUser = DynosaurSetup.User("Alice", 30, "alice@example.com", 42L, Some("Scala developer"))
  val dEncoded = DynosaurSetup.schema.write(dUser).toOption.get

  locally:
    assert(codec.decode(encoded) == Right(user), "zio-blocks round-trip failed")
    assert(DynosaurSetup.schema.read(dEncoded).isRight, "dynosaur round-trip failed")

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
class EncodeBench:
  val s = new BenchState()
  @Benchmark def dynosaur(bh: Blackhole): Unit = bh.consume(DynosaurSetup.schema.write(s.dUser))
  @Benchmark def zioBlocks(bh: Blackhole): Unit =
    val m = new java.util.HashMap[String, AV](); s.codec.encode(s.user, m); bh.consume(m)

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
class DecodeBench:
  val s = new BenchState()
  @Benchmark def dynosaur(bh: Blackhole): Unit = bh.consume(DynosaurSetup.schema.read(s.dEncoded))
  @Benchmark def zioBlocks(bh: Blackhole): Unit = bh.consume(s.codec.decode(s.encoded))

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
class RoundTripBench:
  val s = new BenchState()
  @Benchmark def dynosaur(bh: Blackhole): Unit =
    val w = DynosaurSetup.schema.write(s.dUser).toOption.get; bh.consume(DynosaurSetup.schema.read(w))
  @Benchmark def zioBlocks(bh: Blackhole): Unit =
    val m = new java.util.HashMap[String, AV](); s.codec.encode(s.user, m); bh.consume(s.codec.decode(m))
