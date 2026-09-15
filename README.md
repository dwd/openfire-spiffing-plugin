# Spiffing for Openfire

An Openfire plugin for server clearance enforcement using [XEP-0258](https://xmpp.org/extensions/xep-0258.html)
and [Spiffing Java](https://github.com/dwd/spiffing-java).

Configure a policy, server clearance, and default message label in **Server → Server
Settings → Spiffing security labels**. Saving checks both policy validity and
clearance authorization of the default. Inbound messages are checked before
routing; unlabelled messages are stamped with that default.

Administrators can also curate a named label catalogue in **Server → Server
Settings → Spiffing label catalogue**, answering XEP-0258 catalogue discovery
(`urn:xmpp:sec-label:catalog:2`) for local clients.

Requires **Openfire 5.0.0 or later running Java 17 or later**. The Java requirement
comes from Spiffing. This is the first server enforcement increment, not a full
implementation of every XEP-0258 feature.

## Build and test

With Spiffing checked out alongside this repository:

```sh
mvn -f ../spiffing-java/pom.xml install
mvn verify
```

The dependency is `io.cridland:spiffing:1.0-SNAPSHOT`. CI builds its source at
`60c474434fc57f9a1ecab7e9549773f3a6656614` before building this plugin.
The plugin tests use copied, MIT-licensed Food policy fixtures; no sibling source
checkout is required after installing the dependency.

Install `target/spiffing-openfire-plugin-assembly.jar` using Openfire's plugin
upload page. The archive contains the plugin and Spiffing. Bouncy Castle is supplied by Openfire.
**Once installed, ordinary inbound messages are blocked until valid settings are
saved.** No sample policy or clearance is activated automatically. On first
install, Openfire creates the `ofSpiffingCatalog` database table from
`src/main/database/spiffing_*.sql` for the label catalogue.

## Configuration

Paste an Open XML SPIF policy and the clearance/default label payloads. Select
input formats independently:

| Selection | Label | Clearance |
| --- | --- | --- |
| ESS | Base64 ESS BER/DER | Base64 RFC 5912 BER/DER |
| NATO | ADaTP-4774 originator confidentiality XML | NATO confidentiality clearance XML |
| XML | Spiffy XML | Spiffy XML |

Paste the label itself, without a `securitylabel` envelope. Choose the output
format used when stamping defaults; ESS is the initial selection. The plugin
generates the XEP-0258 envelope and policy-derived display marking. Existing
permitted labels retain their original encoding and marking.

Configuration is stored atomically in **`OPENFIRE_HOME/conf/spiffing.xml`**. The
Openfire process must be able to write that directory. Save changes through the
Admin Console; manual file changes take effect on plugin restart. Invalid saves
preserve the previous configuration. A missing, unreadable, or invalid file at
startup blocks ordinary messages. Back up this file with the server configuration.
Configuration is per node; automatic cluster distribution is not implemented.

## Enforcement scope

- All non-error message types entering Openfire's inbound message router are
  checked, including local-client and federated traffic. Empty `<label/>` requests
  are stamped with the default too.
- Both label validity and clearance access must pass. Classification hierarchy
  does not grant implicit access to lower classifications: Spiffing uses explicit
  clearance membership and policy category rules.
- Unknown policies, unsupported formats, invalid labels, denied labels, duplicate
  envelopes, and unverified cross-policy equivalents are rejected with a sanitized
  `forbidden` error. Missing configuration uses `service-unavailable`.
- Error messages bypass authorization and stamping, as XEP-0258 requires. Error
  messages containing a direct XEP-0258 label are discarded without a reply.
- Only the outer message's direct label authorizes that message. Forwarded inner
  messages are not recursively authorized. Per-user/per-room clearances, history
  filtering, and cross-policy translation are outside this increment.

## Label catalogue

- Catalogue entries (name, optional selector, format, and label) are added and
  removed in the Admin Console and stored in the Openfire database.
- `<catalog/>` IQ requests (`urn:xmpp:sec-label:catalog:2`) are answered only for
  local clients; remote/federated requests are rejected with `not-authorized`.
- Each entry is validated against the active policy and clearance both when
  added and again whenever the catalogue is requested; an entry that no longer
  validates after a policy/clearance change is silently omitted rather than
  failing the whole catalogue.
- At most one entry can be flagged as the catalogue's default item, independent
  from the message-stamping default label configured on the settings page.

See [the design document](doc/design.md) for decisions, tests, and remaining
integration work.
