package com.example.meshlink.domain.model.device

data class Contact(
    val account: Account,
    val profile: Profile?
) : Comparable<Contact> {
    val peerId: String get() = account.peerId
    val username: String? get() = profile?.username
    val imageFileName: String? get() = profile?.imageFileName

    override fun compareTo(other: Contact) = compareValuesBy(this, other, Contact::username)
}