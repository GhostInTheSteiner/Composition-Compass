class LastFMQuery(options: CompositionCompassOptions) : IQuery {
    override val requiredFields: List<Fields> get() = listOf()
    override val exclusiveFields: List<Fields> get() = listOf(Fields.Genre, Fields.Track, Fields.Artist)
    override val supportedFields: List<Fields> get() = listOf(Fields.Genre, Fields.Track, Fields.Artist)


    override fun clear() {
        TODO("Not yet implemented")
    }

    override suspend fun prepare() {
        TODO("Not yet implemented")
    }

}
