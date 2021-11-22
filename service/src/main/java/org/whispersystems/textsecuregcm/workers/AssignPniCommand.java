/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.workers;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.jdbi.v3.core.Jdbi;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.auth.ExternalServiceCredentialGenerator;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.metrics.PushLatencyManager;
import org.whispersystems.textsecuregcm.push.ClientPresenceManager;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.securebackup.SecureBackupClient;
import org.whispersystems.textsecuregcm.securestorage.SecureStorageClient;
import org.whispersystems.textsecuregcm.sqs.DirectoryQueue;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.Accounts;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.DeletedAccounts;
import org.whispersystems.textsecuregcm.storage.DeletedAccountsManager;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.storage.FaultTolerantDatabase;
import org.whispersystems.textsecuregcm.storage.Keys;
import org.whispersystems.textsecuregcm.storage.MessagesCache;
import org.whispersystems.textsecuregcm.storage.MessagesDynamoDb;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.textsecuregcm.storage.PhoneNumberIdentifiers;
import org.whispersystems.textsecuregcm.storage.Profiles;
import org.whispersystems.textsecuregcm.storage.ProfilesManager;
import org.whispersystems.textsecuregcm.storage.ReportMessageDynamoDb;
import org.whispersystems.textsecuregcm.storage.ReportMessageManager;
import org.whispersystems.textsecuregcm.storage.ReservedUsernames;
import org.whispersystems.textsecuregcm.storage.StoredVerificationCodeManager;
import org.whispersystems.textsecuregcm.storage.Usernames;
import org.whispersystems.textsecuregcm.storage.UsernamesManager;
import org.whispersystems.textsecuregcm.storage.VerificationCodeStore;
import org.whispersystems.textsecuregcm.util.DynamoDbFromConfig;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.codahale.metrics.MetricRegistry.name;

public class AssignPniCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  public AssignPniCommand() {

    super(new Application<>() {
      @Override
      public void run(final WhisperServerConfiguration whisperServerConfiguration, final Environment environment) {
      }
    }, "assign-pni", "Assigns a PNI to one or more users if they don't already have one");
  }

  @Override
  public void configure(final Subparser subparser) {
    super.configure(subparser);

    subparser.addArgument("-b")
        .dest("base64EncodedUuid")
        .type(String.class)
        .required(true)
        .nargs("*")
        .help("the base64-encoded UUID (ACI) for the user to whom to assign a PNI");
  }

  @Override
  protected void run(final Environment environment,
      final Namespace namespace,
      final WhisperServerConfiguration configuration) throws Exception {

    Clock clock = Clock.systemUTC();
    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    JdbiFactory jdbiFactory = new JdbiFactory();
    Jdbi accountJdbi = jdbiFactory.build(environment, configuration.getAccountsDatabaseConfiguration(), "accountdb");
    FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("account_database_delete_user", accountJdbi,
        configuration.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
    ClientResources redisClusterClientResources = ClientResources.builder().build();

    DynamoDbClient reportMessagesDynamoDb = DynamoDbFromConfig
        .client(configuration.getReportMessageDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient messageDynamoDb = DynamoDbFromConfig.client(configuration.getMessageDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient preKeysDynamoDb = DynamoDbFromConfig.client(configuration.getKeysDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient accountsDynamoDbClient = DynamoDbFromConfig
        .client(configuration.getAccountsDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient deletedAccountsDynamoDbClient = DynamoDbFromConfig
        .client(configuration.getDeletedAccountsDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient phoneNumberIdentifiersDynamoDbClient =
        DynamoDbFromConfig.client(configuration.getPhoneNumberIdentifiersDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster",
        configuration.getCacheClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster rateLimitersCluster = new FaultTolerantRedisCluster("rate_limiters",
        configuration.getRateLimitersCluster(), redisClusterClientResources);

    ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle()
        .executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(4).build();
    ExecutorService backupServiceExecutor = environment.lifecycle()
        .executorService(name(getClass(), "backupService-%d")).maxThreads(8).minThreads(1).build();
    ExecutorService storageServiceExecutor = environment.lifecycle()
        .executorService(name(getClass(), "storageService-%d")).maxThreads(8).minThreads(1).build();

    ExternalServiceCredentialGenerator backupCredentialsGenerator = new ExternalServiceCredentialGenerator(
        configuration.getSecureBackupServiceConfiguration().getUserAuthenticationTokenSharedSecret(), true);
    ExternalServiceCredentialGenerator storageCredentialsGenerator = new ExternalServiceCredentialGenerator(
        configuration.getSecureStorageServiceConfiguration().getUserAuthenticationTokenSharedSecret(), true);

    DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager = new DynamicConfigurationManager<>(
        configuration.getAppConfig().getApplication(), configuration.getAppConfig().getEnvironment(),
        configuration.getAppConfig().getConfigurationName(), DynamicConfiguration.class);
    dynamicConfigurationManager.start();

    DynamoDbClient pendingAccountsDynamoDbClient = DynamoDbFromConfig
        .client(configuration.getPendingAccountsDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    AmazonDynamoDB deletedAccountsLockDynamoDbClient = AmazonDynamoDBClientBuilder.standard()
        .withRegion(configuration.getDeletedAccountsLockDynamoDbConfiguration().getRegion())
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(
                ((int) configuration.getDeletedAccountsLockDynamoDbConfiguration().getClientExecutionTimeout()
                    .toMillis()))
            .withRequestTimeout(
                (int) configuration.getDeletedAccountsLockDynamoDbConfiguration().getClientRequestTimeout()
                    .toMillis()))
        .withCredentials(InstanceProfileCredentialsProvider.getInstance())
        .build();

    DeletedAccounts deletedAccounts = new DeletedAccounts(deletedAccountsDynamoDbClient,
        configuration.getDeletedAccountsDynamoDbConfiguration().getTableName(),
        configuration.getDeletedAccountsDynamoDbConfiguration().getNeedsReconciliationIndexName());
    VerificationCodeStore pendingAccounts = new VerificationCodeStore(pendingAccountsDynamoDbClient,
        configuration.getPendingAccountsDynamoDbConfiguration().getTableName());

    Accounts accounts = new Accounts(accountsDynamoDbClient,
        configuration.getAccountsDynamoDbConfiguration().getTableName(),
        configuration.getAccountsDynamoDbConfiguration().getPhoneNumberTableName(),
        configuration.getAccountsDynamoDbConfiguration().getPhoneNumberIdentifierTableName(),
        configuration.getAccountsDynamoDbConfiguration().getScanPageSize());
    PhoneNumberIdentifiers phoneNumberIdentifiers = new PhoneNumberIdentifiers(phoneNumberIdentifiersDynamoDbClient,
        configuration.getPhoneNumberIdentifiersDynamoDbConfiguration().getTableName());
    Usernames usernames = new Usernames(accountDatabase);
    Profiles profiles = new Profiles(accountDatabase);
    ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
    Keys keys = new Keys(preKeysDynamoDb,
        configuration.getKeysDynamoDbConfiguration().getTableName());
    MessagesDynamoDb messagesDynamoDb = new MessagesDynamoDb(messageDynamoDb,
        configuration.getMessageDynamoDbConfiguration().getTableName(),
        configuration.getMessageDynamoDbConfiguration().getTimeToLive());
    FaultTolerantRedisCluster messageInsertCacheCluster = new FaultTolerantRedisCluster("message_insert_cluster",
        configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster messageReadDeleteCluster = new FaultTolerantRedisCluster("message_read_delete_cluster",
        configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster",
        configuration.getMetricsClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster clientPresenceCluster = new FaultTolerantRedisCluster("client_presence",
        configuration.getClientPresenceClusterConfiguration(), redisClusterClientResources);
    SecureBackupClient secureBackupClient = new SecureBackupClient(backupCredentialsGenerator, backupServiceExecutor,
        configuration.getSecureBackupServiceConfiguration());
    SecureStorageClient secureStorageClient = new SecureStorageClient(storageCredentialsGenerator,
        storageServiceExecutor, configuration.getSecureStorageServiceConfiguration());
    ClientPresenceManager clientPresenceManager = new ClientPresenceManager(clientPresenceCluster,
        Executors.newSingleThreadScheduledExecutor(), keyspaceNotificationDispatchExecutor);
    MessagesCache messagesCache = new MessagesCache(messageInsertCacheCluster, messageReadDeleteCluster,
        keyspaceNotificationDispatchExecutor);
    PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster, dynamicConfigurationManager);
    DirectoryQueue directoryQueue = new DirectoryQueue(
        configuration.getDirectoryConfiguration().getSqsConfiguration());
    UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
    ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
    ReportMessageDynamoDb reportMessageDynamoDb = new ReportMessageDynamoDb(reportMessagesDynamoDb,
        configuration.getReportMessageDynamoDbConfiguration().getTableName(),
        configuration.getReportMessageConfiguration().getReportTtl());
    ReportMessageManager reportMessageManager = new ReportMessageManager(reportMessageDynamoDb, rateLimitersCluster,
        Metrics.globalRegistry, configuration.getReportMessageConfiguration().getCounterTtl());
    MessagesManager messagesManager = new MessagesManager(messagesDynamoDb, messagesCache, pushLatencyManager,
        reportMessageManager);
    DeletedAccountsManager deletedAccountsManager = new DeletedAccountsManager(deletedAccounts,
        deletedAccountsLockDynamoDbClient,
        configuration.getDeletedAccountsLockDynamoDbConfiguration().getTableName());
    StoredVerificationCodeManager pendingAccountsManager = new StoredVerificationCodeManager(pendingAccounts);
    AccountsManager accountsManager = new AccountsManager(accounts, phoneNumberIdentifiers, cacheCluster,
        deletedAccountsManager, directoryQueue, keys, messagesManager, usernamesManager, profilesManager,
        pendingAccountsManager, secureStorageClient, secureBackupClient, clientPresenceManager, clock);

    final List<String> base64EncodedUuids = namespace.getList("base64EncodedUuid");

    for (final String base64EncodedUuid : base64EncodedUuids) {
      final ByteBuffer uuidBuffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64EncodedUuid));
      final UUID aci = new UUID(uuidBuffer.getLong(), uuidBuffer.getLong());

      accountsManager.getByAccountIdentifier(aci).ifPresentOrElse(account -> {
            if (account.getPhoneNumberIdentifier().isEmpty()) {
              final String number = account.getNumber();
              final UUID phoneNumberIdentifier = phoneNumberIdentifiers.getPhoneNumberIdentifier(number);

              accountsManager.update(account, a -> a.setNumber(number, phoneNumberIdentifier));

              System.out.println("Assigned PNI to account: " + aci);
            } else {
              System.out.println("Account already had PNI: " + aci);
            }
          },
          () -> System.out.println("Account not found: " + aci));
    }
  }
}