#!/usr/bin/env python3
"""Pull the real compile errors out of a parse-check log.

The parse check (`tools/parsecheck/`) runs the app's sources with only the Kotlin stdlib,
coroutines and serialization on the classpath — androidx, Compose, Room and okhttp cannot resolve
there and never will, because the sandbox this app is developed in has no route to
dl.google.com. That leaves roughly 3,200 unresolvable-by-design errors with the occasional
genuine one buried among them.

Written after a genuine one — `Assignment type mismatch: kotlin.random.Random` — sat in the log
unnoticed while the build went red in CI twice. The log had it. A hand-rolled grep ending in
`head -30` cut it off, because the noisy file sorted ahead of the broken one.

So this never truncates. It buckets by file, prints a count of what it dropped and why, and puts
the lines it cannot explain away first.

    gradle -p tools/parsecheck compileKotlin 2>&1 | tee /tmp/parse.log
    python3 tools/compile_scan.py /tmp/parse.log [path-fragment ...]

Exits 1 if anything is unexplained, so it can gate a commit.
"""

import collections
import re
import sys

# Absent by design: no Android SDK, no Compose compiler, no okhttp on this classpath.
ABSENT_PACKAGES = re.compile(r"Unresolved reference '(android|androidx|coil|okhttp3|com)'")

ABSENT_SYMBOLS = re.compile(
    r"Unresolved reference '("
    # Room
    r"Dao|Entity|Query|Insert|PrimaryKey|Index|Database|TypeConverters|TypeConverter|"
    r"RoomDatabase|Migration|SupportSQLiteDatabase|OnConflictStrategy|Room|Upsert|Transaction|"
    r"ColumnInfo|execSQL|Context|"
    # Navigation
    r"NavHost|NavHostController|composable|navigate|popUpTo|graph|saveState|launchSingleTop|"
    r"restoreState|rememberNavController|destination|arguments|hierarchy|findStartDestination|"
    r"Scaffold|NavigationBar|NavigationBarItem|NavigationBarItemDefaults|calculateBottomPadding|"
    r"fadeIn|fadeOut|slideInVertically|slideOutVertically|"
    # okhttp / coil
    r"OkHttpClient|Request|Response|ImageLoader|newCall|isSuccessful|body|"
    # Compose and lifecycle
    r"Composable|Modifier|MaterialTheme|Text|Column|Row|Box|Spacer|Button|OutlinedButton|Card|"
    r"Icon|Icons|IconButton|Color|Brush|Offset|dp|sp|CircleShape|RoundedCornerShape|ImageVector|"
    r"ViewModel|viewModelScope|viewModel|viewModelFactory|initializer|collectAsStateWithLifecycle|"
    r"getValue|setValue|remember|mutableStateOf|Alignment|FontWeight|TextAlign|Arrangement|"
    r"LazyColumn|LazyRow|PaddingValues|ColumnScope|RowScope|item|items|CompositionLocalProvider|"
    r"animateFloatAsState|animateColorAsState|animateDpAsState|tween|rememberScrollState|"
    r"clip|background|clickable|padding|size|weight|fillMaxSize|fillMaxWidth|fillMaxHeight|"
    r"height|width|aspectRatio|systemBarsPadding|statusBarsPadding|verticalScroll|Canvas|"
    # The app's own Composables, which are only unresolvable because Compose is
    r"SmartCard|EmptyState|FactImage|SmartPalette|MasteryRing|CategoryChip|StatTile|"
    r"SessionProgress|GoDeeper|MascotHost|accent|secondary|gradient)'",
)

# Once a symbol above is unknown, everything that depends on it is unknown too. These are
# consequences of the missing classpath, not findings.
KNOCK_ON = re.compile(
    r"Cannot infer type|Not enough information|ERROR CLASS|Symbol not found|"
    r"Overload resolution ambiguity|overrides nothing|'return' is prohibited here|"
    r"Illegal annotation|it is internal in file|Function invocation 'run\(\.\.\.\)' expected|"
    r"Argument type mismatch: actual type is '(R|K|T|kotlin\.collections\.List<T>)'",
)


def scan(path, fragments):
    real, explained = collections.defaultdict(list), collections.Counter()
    for line in open(path):
        if not line.startswith("e: "):
            continue
        if fragments and not any(f in line for f in fragments):
            continue
        if ABSENT_PACKAGES.search(line):
            explained["absent package"] += 1
        elif ABSENT_SYMBOLS.search(line):
            explained["absent symbol"] += 1
        elif KNOCK_ON.search(line):
            explained["knock-on"] += 1
        else:
            tail = line.split("/com/hillelsht/smart/")[-1].strip()
            real[tail.split(":")[0]].append(tail)
    return real, explained


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    real, explained = scan(argv[1], argv[2:])
    total = sum(len(v) for v in real.values())

    for reason, count in explained.most_common():
        print(f"  {count:>5} explained away: {reason}")
    print(f"\n{total} error(s) this cannot explain — read every one, they are not truncated:\n")
    for name, lines in sorted(real.items(), key=lambda kv: len(kv[1])):
        print(f"--- {name}")
        for line in lines:
            print(f"    {line}")
    return 1 if total else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except BrokenPipeError:
        # Piping into `head` is the obvious way to skim this, and closing the pipe early would
        # otherwise print a traceback that reads exactly like the tool itself failing.
        sys.stderr.close()
        sys.exit(1)
