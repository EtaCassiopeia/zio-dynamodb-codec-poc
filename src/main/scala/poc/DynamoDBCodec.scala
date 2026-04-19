package poc

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

/** Bidirectional codec between Scala values and DynamoDB AttributeValue.
  *
  * Dual surface: top-level items are Map[String, AttributeValue],
  * nested fields are single AttributeValue instances.
  */
abstract class DynamoDBCodec[A]:
  def encode(value: A, output: java.util.Map[String, AttributeValue]): Unit
  def decode(input: java.util.Map[String, AttributeValue]): Either[String, A]
  def encodeValue(value: A): AttributeValue
  def decodeValue(av: AttributeValue): Either[String, A]

object DynamoDBCodec:

  def primitive[A](enc: A => AttributeValue, dec: AttributeValue => Either[String, A]): DynamoDBCodec[A] =
    new DynamoDBCodec[A]:
      def encode(value: A, output: java.util.Map[String, AttributeValue]): Unit =
        throw new UnsupportedOperationException("Primitive cannot encode to top-level map")
      def decode(input: java.util.Map[String, AttributeValue]): Either[String, A] =
        Left("Primitive cannot decode from top-level map")
      def encodeValue(value: A): AttributeValue = enc(value)
      def decodeValue(av: AttributeValue): Either[String, A] = dec(av)

  def record[A](
      enc: (A, java.util.Map[String, AttributeValue]) => Unit,
      dec: java.util.Map[String, AttributeValue] => Either[String, A]
  ): DynamoDBCodec[A] =
    new DynamoDBCodec[A]:
      def encode(value: A, output: java.util.Map[String, AttributeValue]): Unit = enc(value, output)
      def decode(input: java.util.Map[String, AttributeValue]): Either[String, A] = dec(input)
      def encodeValue(value: A): AttributeValue =
        val map = new java.util.HashMap[String, AttributeValue]()
        enc(value, map)
        AttributeValue.builder().m(map).build()
      def decodeValue(av: AttributeValue): Either[String, A] =
        if av.hasM then dec(av.m()) else Left("Expected M attribute")
