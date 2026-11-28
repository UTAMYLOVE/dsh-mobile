package app.agency.core.store

class MemStoreTest : StoreContract() {
    override fun newStore(): Store = MemStore()
}
