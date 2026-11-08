# reference

A verbatim copy of the original library sources, taken from
`demos/SBF/ns/sbf2asc-kotlin/sbf/src/main/kotlin/sbf/` (commit `175d188` plus the working-tree
change that renames `RefEpochSigMax` to `REF_EPOCH_SIG_MAX`).

It exists only so `verify` and `bench` can compare the new library against the original, in the
same JVM and on the same data. It keeps its original package (`sbf`), so it cannot clash with the
new library (`ruggedmobile.sbf`).

**Do not edit these files.** If the original library changes, copy the files again and re-run
`verify`.
