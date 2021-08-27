class YouTubeQuery(options: CompositionCompassOptions) : IQuery {
    override val requiredFields: List<Fields> get() = listOf(Fields.SearchQuery)
    override val exclusiveFields: List<Fields> get() = listOf()
    override val supportedFields: List<Fields> get() = listOf(Fields.SearchQuery)

    override fun clear() {
        TODO("Not yet implemented")
    }

    override suspend fun prepare() {
        TODO("Not yet implemented")
    }

}
