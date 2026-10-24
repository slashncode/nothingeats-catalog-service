package io.github.slashncode.nothingeats.example.app

import io.github.slashncode.nothingeats.platform.core.config.loadConfig

/** `serve` (default), `migrate`, or `cron <task>`. */
fun main(args: Array<String>) {
    ServiceComponent::class.create(loadConfig<ServiceConfig>()).runner.run(args)
}
