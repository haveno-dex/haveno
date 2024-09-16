# Flatpak distribution

The `.flatpak` binary files (known as "bundles") that
`./gradlew packageInstallers` creates can be used to download and install
Haveno, but there are several security issues that arise in Flatpak when only
using the bundle files:

- There is no
[digital signature](https://en.wikipedia.org/wiki/Digital_signature),
if a bad actor were to upload a malicious `.flatpak` the users would have no
way to tell when upgrading.
- Upgrading isn't as easy, your users need to find the new Flatpak bundle file,
and you cannot update multiple apps easily.
  - This also makes an accidental downgrade much more likely.

Flatpak has a solution for these issues, a
[Flatpak repository](https://docs.flatpak.org/en/latest/repositories.html).
Flatpak repos store the data of their apps within an OSTree (almost like git)
repository, and the commits can be signed with a GPG key. The nature of OSTree
also allows for easy updates, as the Flatpak client can download deltas of the
changes instead of the entire file.

If you plan on distributing Haveno as a Flatpak, it's recommended to create a
Flatpak repository as well. This guide will show you how to create a Flatpak
repository for Haveno. The official documentation states that [it's possible to
use GitHub/Lab Pages](https://docs.flatpak.org/en/latest/hosting-a-repository.html#hosting-a-repository-on-gitlab-github-pages)
to host the repository, but this hasn't been tested. The more common way is to
use a web server, or something like
[flat-manager](https://github.com/flatpak/flat-manager).

An example Haveno flat-manager solution using `docker-compose` has been created
and documented at <https://gitlab.com/Jabster28/flatman-haveno-test.git> if you
want a quick way to get started. Note that this does require an always-on server.
