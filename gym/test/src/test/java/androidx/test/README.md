# Stand-ins for `androidx.test:monitor`

Robolectric calls a handful of classes from `androidx.test:monitor` while it starts the app and
moves an Activity through its lifecycle. That library is published only on Google's Maven
repository, which these builds deliberately do not depend on (everything else comes from Maven
Central), so the few classes Robolectric touches are declared here with the same names and method
signatures and no behaviour: nothing in these tests listens to them.

If a newer Robolectric fails with `NoClassDefFoundError: androidx/test/...`, add the missing class
here the same way (or, where Google's Maven is reachable, delete this folder, remove the
`androidx.*` exclusions in `pom.xml` and add that repository instead).
