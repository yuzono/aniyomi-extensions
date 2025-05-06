#!/usr/bin/env python3

from concurrent.futures import Future, ThreadPoolExecutor, as_completed
import itertools
from pathlib import Path
import re
import subprocess
import sys
from collections import deque # Add this import

VERSION_STR = "VersionCode ="
VERSION_REGEX_KMK = re.compile(f"(?<=Kmk){VERSION_STR} (\\d+)")
VERSION_REGEX_UPSTREAM = re.compile(f"(?<!Kmk){VERSION_STR} (\\d+)")
BUMPED_FILES: list[Path] = []

def has_match(query: str, file: Path) -> tuple[Path, bool]:
    return (file, query in file.read_text())

def find_libs_with_match(query: str, lib_name_regex: re.Pattern) -> list[str]:
    files = Path("lib").glob("*/build.gradle.kts")

    # Use multiple threads to find matches.
    with ThreadPoolExecutor() as executor:
        futures = [executor.submit(has_match, query, file) for file in files]
        results = map(Future.result, as_completed(futures))
        lib_path_list = [path for path, result in results if result]
        # Use the passed lib_name_regex
        return [match.group(1) for match in filter(None, (lib_name_regex.search(str(lib_path_obj)) for lib_path_obj in lib_path_list))]

def find_files_with_match(query: str, include_multisrc: bool = True) -> list[Path]:
    files = Path("src").glob("*/*/build.gradle")
    if include_multisrc:
        files = itertools.chain(files, Path("lib-multisrc").glob("*/build.gradle.kts"))

    # Prevent bumping files twice.
    files = filter(lambda file: file not in BUMPED_FILES, files)

    # Use multiple threads to find matches.
    with ThreadPoolExecutor() as executor:
        futures = [executor.submit(has_match, query, file) for file in files]
        results = map(Future.result, as_completed(futures))
        return [path for path, result in results if result]

def replace_version(match: re.Match) -> str:
    version = int(match.group(1))
    print(f"{version} -> {version + 1}")
    return f"{VERSION_STR} {version + 1}"

def bump_version(file: Path):
    BUMPED_FILES.append(file)
    with file.open("r+") as f:
        print(f"\n{file}: ", end="")
        regex = VERSION_REGEX_KMK if len(sys.argv) > 1 and sys.argv[1].lower() == 'true' else VERSION_REGEX_UPSTREAM
        text = regex.sub(replace_version, f.read())
        # Move the cursor to the start again, to prevent writing at the end
        f.seek(0)
        f.write(text)

def bump_lib_multisrc(theme: str):
    for file in find_files_with_match(f"themePkg = '{theme}'", include_multisrc=False):
        bump_version(file)

def commit_changes():
    paths = [str(path.resolve()) for path in BUMPED_FILES]
    subprocess.check_call(["git", "add"] + paths)
    commit_message = "[skip ci] chore: Mass-bump on extensions"
    if len(sys.argv) > 2:
        commit_message += f"\n\nCaused by: {sys.argv[2]}"
    subprocess.check_call(["git", "commit", "-m", commit_message])
    # 'git push' will be doing outside of this script so we can decide per workflow if we want to push or not.
    # subprocess.check_call(["git", "push"])

if __name__ == "__main__" and len(sys.argv) > 3:
    # Regex to match the lib name in the path, like "unpacker" or "dood-extractor".
    lib_name_extractor_regex = re.compile(r"lib/([a-z0-9-]+)/")

    libs_to_discover_q = deque() # Queue for the discovery process
    all_libs_to_process = set() # Set of all unique library names found

    # Initial population of the discovery queue and the set of all libs
    initial_matches_from_args = filter(None, map(lib_name_extractor_regex.search, sys.argv[3:]))
    for match_obj in initial_matches_from_args:
        lib_name = match_obj.group(1)
        if lib_name not in all_libs_to_process:
            libs_to_discover_q.append(lib_name)
            all_libs_to_process.add(lib_name)
            print(f"Initial lib for discovery: {lib_name}")

    # Phase 1: Discover all transitive dependencies
    print("\n--- Starting Dependency Discovery Phase ---")
    discovery_iteration_count = 0
    while libs_to_discover_q:
        discovery_iteration_count += 1
        current_lib_name_for_discovery = libs_to_discover_q.popleft()
        project_path_for_discovery = f":lib:{current_lib_name_for_discovery}"
        print(f"Discovery Iteration {discovery_iteration_count}: Scanning dependencies for {project_path_for_discovery}")

        dependent_lib_names = find_libs_with_match(project_path_for_discovery, lib_name_extractor_regex)
        for dep_lib_name in dependent_lib_names:
            if dep_lib_name not in all_libs_to_process:
                libs_to_discover_q.append(dep_lib_name)
                all_libs_to_process.add(dep_lib_name)
                print(f"  Discovered and queued new dependent lib: {dep_lib_name}")

    print("\n--- Dependency Discovery Complete ---")

    if not all_libs_to_process:
        print("No libraries (initial or dependent) were identified for processing.")
    else:
        print(f"All libraries identified for version bumping: {all_libs_to_process}")
        print("\n--- Starting Version Bumping Phase ---")
        # Phase 2: Perform version bumping for all discovered libraries
        for lib_name_to_bump in all_libs_to_process:
            project_path_for_bumping = f":lib:{lib_name_to_bump}"
            print(f"\nProcessing version bump for project: {project_path_for_bumping}")

            files_to_check_for_bumping = find_files_with_match(project_path_for_bumping)
            if not files_to_check_for_bumping:
                print(f"  No files found matching query '{project_path_for_bumping}' for version bumping.")

            for file_to_bump in files_to_check_for_bumping:
                if file_to_bump.parent.parent.name == "lib-multisrc":
                    theme_name = file_to_bump.parent.name
                    print(f"    Bumping multisrc theme: {theme_name} (triggered by {project_path_for_bumping})")
                    bump_lib_multisrc(theme_name)
                else:
                    print(f"    Bumping single extension: {file_to_bump} (triggered by {project_path_for_bumping})")
                    bump_version(file_to_bump)

    if len(BUMPED_FILES) > 0:
        print("\nCommitting changes...")
        commit_changes()
    elif all_libs_to_process: # If we processed something but bumped no files
        print("\nProcessing complete. No files were bumped.")
        # The case where all_libs_to_process is empty is handled above by the 'if not all_libs_to_process:' block
