package io.github.slashncode.nothingeats.catalog.app

import io.github.slashncode.nothingeats.catalog.adapters.http.CatalogRoutes
import io.github.slashncode.nothingeats.catalog.adapters.messaging.OutboxCatalogEvents
import io.github.slashncode.nothingeats.catalog.adapters.persistence.ExposedDishes
import io.github.slashncode.nothingeats.catalog.adapters.persistence.ExposedRestaurants
import io.github.slashncode.nothingeats.catalog.adapters.persistence.ExposedTransactions
import io.github.slashncode.nothingeats.catalog.adapters.seed.LoadSeedTask
import io.github.slashncode.nothingeats.catalog.application.BrowseCatalog
import io.github.slashncode.nothingeats.catalog.application.CatalogEvents
import io.github.slashncode.nothingeats.catalog.application.Dishes
import io.github.slashncode.nothingeats.catalog.application.LoadSeed
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.Restaurants
import io.github.slashncode.nothingeats.catalog.application.Transactions
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.kafka.OutboxRelay
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes and cron tasks are collected with `@IntoSet`.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val cronTasks: Set<CronTask>

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, JwtAuth(config.jwt), extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Messaging

    @ServiceScope
    @Provides
    fun outbox(clock: Clock): Outbox = Outbox(source = "/nothingeats/catalog", clock = clock)

    @ServiceScope
    @Provides
    fun outboxRelay(postgres: PostgresDatabase): OutboxRelay = OutboxRelay(postgres, KafkaClients.producer(config.kafka, "catalog-service"))

    @IntoSet
    @Provides
    fun outboxRelayLifecycle(relay: OutboxRelay): LifecycleComponent = relay

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Catalog

    @Provides
    fun restaurants(postgres: PostgresDatabase): Restaurants = ExposedRestaurants(postgres)

    @Provides
    fun dishes(postgres: PostgresDatabase): Dishes = ExposedDishes(postgres)

    @Provides
    fun transactions(postgres: PostgresDatabase): Transactions = ExposedTransactions(postgres)

    @Provides
    fun catalogEvents(
        outbox: Outbox,
        postgres: PostgresDatabase,
    ): CatalogEvents = OutboxCatalogEvents(outbox, postgres)

    @Provides
    fun browseCatalog(
        restaurants: Restaurants,
        dishes: Dishes,
    ): BrowseCatalog = BrowseCatalog(restaurants, dishes)

    @Provides
    fun manageCatalog(
        restaurants: Restaurants,
        dishes: Dishes,
        events: CatalogEvents,
        transactions: Transactions,
    ): ManageCatalog = ManageCatalog(restaurants, dishes, events, transactions)

    @IntoSet
    @Provides
    fun catalogRoutes(
        browse: BrowseCatalog,
        manage: ManageCatalog,
    ): HttpRoutes = CatalogRoutes(browse, manage)

    @IntoSet
    @Provides
    fun loadSeed(manage: ManageCatalog): CronTask = LoadSeedTask(LoadSeed(manage))
}
