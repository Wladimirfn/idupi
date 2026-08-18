package com.example.idupi.domain.repository

/**
 * Dependency-injection seam for [IduPiClient]. ViewModels depend on this
 * interface instead of reaching into the [com.example.idupi.data.IduPiClientProvider]
 * singleton directly, which makes them testable with fakes.
 */
interface IduPiClientSource {
    val client: IduPiClient
}
