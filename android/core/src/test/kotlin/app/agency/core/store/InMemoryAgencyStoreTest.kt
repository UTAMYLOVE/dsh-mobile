package app.agency.core.store

class InMemoryAgencyStoreTest : AgencyStoreContract() {
    override fun newStore(): AgencyStore = InMemoryAgencyStore()
}
