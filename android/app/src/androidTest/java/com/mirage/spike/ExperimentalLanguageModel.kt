package com.mirage.spike

/** Opt-in advanced interpretation coverage; excluded from the offline product acceptance suite. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ExperimentalLanguageModel
