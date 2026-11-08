package com.kilowhat.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KilowhatApplication

fun main(args: Array<String>) {
    runApplication<KilowhatApplication>(*args)
}
