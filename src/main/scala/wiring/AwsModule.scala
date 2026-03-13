package wiring

import org.typelevel.log4cats.Logger

import cats.effect.*
import cats.syntax.all.*
import config.AppConfig
import storage.AwsClients
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

/** Bundles AWS SDK clients used by stores. Main and StoreModule may use
  * storage.AwsClients directly; this module is for optional composed wiring.
  */
case class AwsModule[F[_]](
    dynamoDb: DynamoDbAsyncClient,
    kinesis: Option[KinesisAsyncClient],
)

object AwsModule:
  def resource[F[_]: Async: Logger](
      config: AppConfig,
  ): Resource[F, AwsModule[F]] =
    for
      dynamoClient <- AwsClients.dynamoDbClient[F](config.aws, config.dynamodb)
      kinesisClient <-
        if config.kinesis.enabled then
          AwsClients.kinesisClient[F](config.aws, config.dynamodb).map(Some(_))
        else Resource.pure[F, Option[KinesisAsyncClient]](None)
    yield AwsModule(dynamoClient, kinesisClient)
