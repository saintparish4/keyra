package storage

import java.net.URI

import cats.effect.*
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import config.AwsConfig

/** Factory for creating DynamoDB async clients with proper resource management.
  */
object DynamoDBClient:

  /** Create a managed DynamoDB client.
    *
    * The client is wrapped in a Resource to ensure proper cleanup when done.
    * Supports both real AWS and LocalStack configurations.
    */
  def create[F[_]: Async](config: AwsConfig): Resource[F, DynamoDbAsyncClient] =
    Resource.make {
      Async[F].delay {
        val builder = DynamoDbAsyncClient.builder()
          .region(Region.of(config.region))

        // Configure for LocalStack if needed
        val configuredBuilder =
          if config.localstack then
            val endpoint =
              if config.endpoint.nonEmpty then config.endpoint
              else "http://localhost:4566"

            builder.endpointOverride(URI.create(endpoint))
              .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"),
              ))
          else builder.credentialsProvider(DefaultCredentialsProvider.create())

        configuredBuilder.build()
      }
    }(client => Async[F].delay(client.close()))

  /** Create a client with explicit credentials (for testing).
    */
  def withCredentials[F[_]: Async](
      region: String,
      accessKeyId: String,
      secretAccessKey: String,
      endpoint: Option[String] = None,
  ): Resource[F, DynamoDbAsyncClient] = Resource.make(Async[F].delay {
    val builder = DynamoDbAsyncClient.builder().region(Region.of(region))
      .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey),
      ))

    endpoint.foreach(ep => builder.endpointOverride(URI.create(ep)))

    builder.build()
  })(client => Async[F].delay(client.close()))
