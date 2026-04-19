package poc

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import zio.blocks.docs.Doc
import zio.blocks.schema.*
import zio.blocks.schema.binding.*
import zio.blocks.schema.derive.*
import zio.blocks.typeid.TypeId

/** Minimal DynamoDB codec deriver — derives codecs automatically from `case class ... derives Schema`.
  *
  * Architecture:
  *   1. At derivation time, pre-compute a FieldInfo array (field name, register, codec)
  *   2. At encode time, deconstruct value into registers, loop over FieldInfo, put into HashMap
  *   3. At decode time, loop over FieldInfo, get from HashMap, set registers, construct value
  *
  */
object DynamoDBCodecDeriver extends Deriver[DynamoDBCodec]:

  private class FieldInfo(val name: String, val register: Register[?], val isOptional: Boolean):
    var codec: DynamoDBCodec[Any] = null      // for non-optional: the field codec; for optional: the INNER type codec
    def get(regs: Registers): Any = register.asInstanceOf[Register[Any]].get(regs, 0)
    def set(regs: Registers, v: Any): Unit = register.asInstanceOf[Register[Any]].set(regs, 0, v)
  
  override def derivePrimitive[A](
      primitiveType: PrimitiveType[A], typeId: TypeId[A], binding: Binding.Primitive[A],
      doc: Doc, modifiers: Seq[Modifier.Reflect], defaultValue: Option[A], examples: Seq[A]
  ): Lazy[DynamoDBCodec[A]] =
    Lazy((primitiveType match
      case _: PrimitiveType.String  => DynamoDBCodec.primitive[String](
          a => AttributeValue.builder().s(a).build(),
          av => if av.s() != null then Right(av.s()) else Left("Expected S"))
      case _: PrimitiveType.Int     => DynamoDBCodec.primitive[Int](
          a => AttributeValue.builder().n(a.toString).build(),
          av => if av.n() != null then Right(av.n().toInt) else Left("Expected N"))
      case _: PrimitiveType.Long    => DynamoDBCodec.primitive[Long](
          a => AttributeValue.builder().n(a.toString).build(),
          av => if av.n() != null then Right(av.n().toLong) else Left("Expected N"))
      case _: PrimitiveType.Boolean => DynamoDBCodec.primitive[Boolean](
          a => AttributeValue.builder().bool(a).build(),
          av => if av.bool() != null then Right(av.bool().booleanValue()) else Left("Expected BOOL"))
      case _ => DynamoDBCodec.primitive[Any](
          a => AttributeValue.builder().s(a.toString).build(),
          av => if av.s() != null then Right(av.s()) else Left("Expected S"))
    ).asInstanceOf[DynamoDBCodec[A]])
  
  override def deriveRecord[F[_, _], A](
      fields: IndexedSeq[Term[F, A, ?]], typeId: TypeId[A], binding: Binding.Record[A],
      doc: Doc, modifiers: Seq[Modifier.Reflect], defaultValue: Option[A], examples: Seq[A]
  )(implicit hb: HasBinding[F], hi: HasInstance[F]): Lazy[DynamoDBCodec[A]] =
    val n = fields.size
    val constructor = binding.constructor
    val deconstructor = binding.deconstructor
    val usedRegs = constructor.usedRegisters

    // Get typed register objects for direct field access
    val registers = Reflect.Record[Binding, A](
      fields.map(t => Term[Binding, A, Any](t.name, t.value.asInstanceOf[Reflect[Binding, Any]], t.doc, t.modifiers)),
      typeId, binding, doc, modifiers, None, Seq.empty
    ).registers

    Lazy {
      // Pre-compute FieldInfo array (done once at derivation time)
      val infos = new Array[FieldInfo](n)
      var i = 0
      while i < n do
        val f = fields(i)
        val name = f.modifiers.collectFirst { case m: Modifier.rename => m.name }.getOrElse(f.name)
        val isOpt = f.value.asVariant.exists(_.typeId.name == "Option")
        infos(i) = new FieldInfo(name, registers(i), isOpt)
        if isOpt then
          // For Option[T], resolve the INNER type codec (T, not Option[T])
          // so encode/decode in the record loop works on the unwrapped value
          f.value.asVariant.foreach { v =>
            v.cases.find(_.name == "Some").foreach { someTerm =>
              someTerm.value.asRecord.foreach { someRecord =>
                if someRecord.fields.nonEmpty then
                  infos(i).codec = instance(someRecord.fields(0).value.metadata.asInstanceOf[F[Any, Any]]).force
              }
            }
          }
          // Fallback to full Option codec if inner extraction fails
          if infos(i).codec == null then
            infos(i).codec = instance(f.value.metadata.asInstanceOf[F[Any, Any]]).force
        else
          infos(i).codec = instance(f.value.metadata.asInstanceOf[F[Any, Any]]).force
        i += 1

      DynamoDBCodec.record[A](
        // ENCODE: deconstruct -> loop FieldInfo -> put into mutable HashMap  [O(N)]
        enc = (value, output) =>
          val regs = Registers(usedRegs)
          deconstructor.deconstruct(regs, 0, value)
          var idx = 0
          while idx < n do
            val fi = infos(idx)
            val v = fi.get(regs)
            if fi.isOptional then
              v match
                case Some(inner) => output.put(fi.name, fi.codec.encodeValue(inner))
                case _           => () // None -> omit field
            else
              output.put(fi.name, fi.codec.encodeValue(v))
            idx += 1
        ,
        // DECODE: loop FieldInfo -> get from HashMap -> set registers -> construct  [O(N)]
        dec = input =>
          val regs = Registers(usedRegs)
          var idx = 0
          var err: String = null
          while idx < n && err == null do
            val fi = infos(idx)
            val raw = input.get(fi.name)
            if raw == null then
              if fi.isOptional then fi.set(regs, None)
              else err = s"Missing: ${fi.name}"
            else if fi.isOptional then
              fi.codec.decodeValue(raw) match
                case Right(v) => fi.set(regs, Some(v))
                case Left(e)  => err = s"${fi.name}: $e"
            else
              fi.codec.decodeValue(raw) match
                case Right(v) => fi.set(regs, v)
                case Left(e)  => err = s"${fi.name}: $e"
            idx += 1
          if err != null then Left(err) else Right(constructor.construct(regs, 0))
      )
    }

  override def deriveVariant[F[_, _], A](
      cases: IndexedSeq[Term[F, A, ?]], typeId: TypeId[A], binding: Binding.Variant[A],
      doc: Doc, modifiers: Seq[Modifier.Reflect], defaultValue: Option[A], examples: Seq[A]
  )(implicit hb: HasBinding[F], hi: HasInstance[F]): Lazy[DynamoDBCodec[A]] =
    // Option is represented as a Variant with Some and None cases
    val someCase = cases.find(_.name == "Some")
    val noneCase = cases.find(_.name == "None")
    if someCase.isDefined && noneCase.isDefined then
      Lazy {
        val someCodec = instance(someCase.get.value.metadata.asInstanceOf[F[Any, Any]]).force
        val noneCodec = instance(noneCase.get.value.metadata.asInstanceOf[F[Any, Any]]).force
        val disc = binding.discriminator
        DynamoDBCodec.primitive[A](
          v => if cases(disc.discriminate(v)).name == "Some" then someCodec.encodeValue(v)
               else AttributeValue.builder().nul(true).build(),
          av => if av.nul() != null && av.nul() then
                  noneCodec.decodeValue(AttributeValue.builder().m(java.util.Collections.emptyMap()).build()).map(_.asInstanceOf[A])
                else someCodec.decodeValue(av).map(_.asInstanceOf[A])
        )
      }
    else Lazy(stub("Variant"))

  override def deriveSequence[F[_, _], C[_], A](e: Reflect[F, A], t: TypeId[C[A]], b: Binding.Seq[C, A], d: Doc, m: Seq[Modifier.Reflect], dv: Option[C[A]], ex: Seq[C[A]])(implicit hb: HasBinding[F], hi: HasInstance[F]): Lazy[DynamoDBCodec[C[A]]] = Lazy(stub("Seq"))
  override def deriveMap[F[_, _], M[_, _], K, V](k: Reflect[F, K], v: Reflect[F, V], t: TypeId[M[K, V]], b: Binding.Map[M, K, V], d: Doc, m: Seq[Modifier.Reflect], dv: Option[M[K, V]], ex: Seq[M[K, V]])(implicit hb: HasBinding[F], hi: HasInstance[F]): Lazy[DynamoDBCodec[M[K, V]]] = Lazy(stub("Map"))
  override def deriveWrapper[F[_, _], A, B](w: Reflect[F, B], t: TypeId[A], b: Binding.Wrapper[A, B], d: Doc, m: Seq[Modifier.Reflect], dv: Option[A], ex: Seq[A])(implicit hb: HasBinding[F], hi: HasInstance[F]): Lazy[DynamoDBCodec[A]] = Lazy(stub("Wrapper"))
  override def deriveDynamic[F[_, _]](b: Binding.Dynamic, d: Doc, m: Seq[Modifier.Reflect], dv: Option[DynamicValue], ex: Seq[DynamicValue])(implicit hb: HasBinding[F], hi: HasInstance[F]): Lazy[DynamoDBCodec[DynamicValue]] = Lazy(stub("Dynamic"))
  private def stub[A](t: String): DynamoDBCodec[A] = DynamoDBCodec.primitive(_ => throw new UnsupportedOperationException(t), _ => Left(t))
