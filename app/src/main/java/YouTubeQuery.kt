class YouTubeQuery(options: CompositionCompassOptions) : IQuery {
    override val requiredFields: List<List<Fields>> get() = listOf(listOf(Fields.SearchQuery))
    override val supportedFields: List<Fields> get() = listOf(Fields.SearchQuery)
    override fun changeMode(mode: QueryMode) {
        //TODO
    }

    override fun clear() {
        //TODO
    }

    override suspend fun prepare() {
        //TODO
    }

}
