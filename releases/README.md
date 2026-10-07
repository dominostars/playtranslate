# releases/

The R8 mapping file of every release build, kept so a crash log from the
field can be read. Release builds shrink and rename the code, and a crash
log then says `i92.onTouchEvent` instead of `RegionDragView.onTouchEvent`.
The mapping file is the dictionary back; without it the log has to be read
by reasoning, and the build directory's copy is overwritten by the next
build.

Every release build that runs R8 (`assembleRelease`, `installRelease`,
`bundleRelease`, or `minifyReleaseWithR8` itself; the `archiveReleaseMapping`
task in `app/build.gradle.kts` finalizes the R8 task) writes

    mapping-<versionName>-<versionCode>-<first 8 of the map id>.txt

Each obfuscated frame in a log carries the map id of its build
(`r8-map-id-<hex>`), so a log and its mapping match by that id, and two
release builds of the same version never overwrite each other.

To read a log:

    scripts/retrace.sh <logcat-or-stack-file>

The mapping files are local only (`.gitignore`); this README is tracked.

## Regenerating a missing mapping

R8 is deterministic for this build: every dependency is pinned in the
version catalog, the JDK comes from the toolchain, and rebuilding a tag
reproduces its map id exactly (`mapping-3.3.0-20-dcb6d56a.txt` was
regenerated this way on 2026-10-07, and its id is the one the 3.3.0 field
logs carry). So a mapping lost with a machine comes back from the tag, in
about three minutes, without touching this checkout:

    X=/tmp/pt-v3.3.0 && mkdir -p "$X"
    git archive v3.3.0 | tar -x -C "$X"
    for p in vendor/OpenCL-Headers vendor/OpenCL-ICD-Loader mnn/third-party/MNN; do
      c=$(git ls-tree v3.3.0 "$p" | awk '{print $3}')
      mkdir -p "$X/$p" && git -C "$p" archive "$c" | tar -x -C "$X/$p"
    done
    cp local.properties "$X/"
    (cd "$X" && ./gradlew :app:minifyReleaseWithR8 --no-daemon)
    head -6 "$X/app/build/outputs/mapping/release/mapping.txt"

The `pg_map_id` line must equal the id in the log; then copy the file here
under the usual name. The submodules are exported at the commits the tag
records, which `git archive` alone leaves out.
