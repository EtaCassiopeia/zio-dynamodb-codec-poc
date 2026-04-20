# ZIO-Blocks DynamoDB Codec — Proof of Concept

Minimal POC: a zio.blocks-based DynamoDB codec vs dynosaur.

**Dynosaur** requires lines of manual schema per record, uses an intermediate `DynamoValue` representation, and merges N single-field maps (O(N^2) encoding).

**This approach** derives codecs from `case class ... derives Schema` with zero boilerplate, encodes directly to `AttributeValue`, and uses a pre-computed field array with register-based access (O(N) encoding).

## Developer experience

**Dynosaur:**
```scala
val schema: DSchema[User] = DSchema.record[User] { field =>
  (field("user_name", _.name),
   field("age", _.age),
   field("email", _.email),
   field("login_count", _.loginCount),
   field.opt("bio", _.bio)
  ).mapN(User.apply)
}
```

**zio-blocks:**
```scala
case class User(
  @Modifier.rename("user_name") name: String,
  age: Int,
  email: String,
  @Modifier.rename("login_count") loginCount: Long,
  bio: Option[String]
) derives Schema

val codec = summon[Schema[User]]
  .deriving[DynamoDBCodec](DynamoDBCodecDeriver).derive
```

## Run the benchmark

```bash
sbt "Jmh/run -i 3 -wi 3 -f 1"
```
