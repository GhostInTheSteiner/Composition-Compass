class FileQuery(options: CompositionCompassOptions) : IQuery {
    override val requiredFields: List<List<Fields>> get() = listOf()
    override val supportedFields: List<Fields> get() = listOf()
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
