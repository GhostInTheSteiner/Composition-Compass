class YouTubeQuery(options: CompositionCompassOptions) : IQuery {
    override val requiredFields: List<Fields> get() = listOf(Fields.SearchQuery)
    override val supportedFields: List<Fields> get() = listOf(Fields.SearchQuery)
    override fun changeMode(mode: QueryMode) {
        TODO("Not yet implemented")
    }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override suspend fun prepare() {
        TODO("Not yet implemented")
    }

}
