# How to contribute

We'd love to accept your patches and contributions to this project.

## Before you begin

### Sign our Contributor License Agreement

Contributions to this project must be accompanied by a
[Contributor License Agreement](https://cla.developers.google.com/about) (CLA).
You (or your employer) retain the copyright to your contribution; this simply
gives us permission to use and redistribute your contributions as part of the
project.

If you or your current employer have already signed the Google CLA (even if it
was for a different project), you probably don't need to do it again.

Visit <https://cla.developers.google.com/> to see your current agreements or to
sign a new one.

### Review our community guidelines

This project follows
[Google's Open Source Community Guidelines](https://opensource.google/conduct/).

## Contribution process

### Code reviews

All submissions, including submissions by project members, require review. We
use GitHub pull requests for this purpose. Consult
[GitHub Help](https://help.github.com/articles/about-pull-requests/) for more
information on using pull requests.

## PR policy

### Format

Code must be formatted according to the
[Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).

The Maven build will automagically run
[`google-java-format`](https://github.com/google/google-java-format) when you
locally build this project.

Pull requests will fail to build if you forget to commit reformatted code, and
cannot be merged until you fix this.

### Single Commit

Pull Requests must contain only a **single commit.**

This is due to how Google replicates this Git repository both into and from its
internal _monorepo_ (see [Wikipedia](https://en.wikipedia.org/wiki/Monorepo) and
[Paper](https://research.google/pubs/why-google-stores-billions-of-lines-of-code-in-a-single-repository/))
with [🦛 Copybara](https://github.com/google/copybara).

When adjusting a PR to code review feedback, please use `git commit --amend`.

You can use `git rebase -i main` to _meld/squash_ existing commits into one.

Then use `git push --force-with-lease` to update the branch of your PR.

We cannot merge your PR until you fix this.

### AI Generated code

It's ok to generate the first draft using AI but we would like code which has
gone through human refinement.

### Alignment with [adk-python](https://github.com/google/adk-python)

We lean on adk-python for being the source of truth and one should refer to
adk-python for validation.

### Javadocs

We want our Javadocs to be concise and meaningful.

## IDEs

### VS Code

This project includes recommended VS Code [extensions](.vscode/extensions.json) and minimal required [settings](.vscode/settings.json).

Those minimal settings are required, and not just a recommendation or preference.
More specifically, they make sure that working with this code in VS Code matches the [code format](#format) expectations of the Maven build.

You may also have other VS Code settings which are your "personal preferences" instead of "project requirements".
We recommend that you keep personal settings in your VS Code User Settings instead. The location of this file varies by platform (e.g., `~/.config/Code/User/settings.json` on Linux).
