merge_legacy_path() (
  source_path="$1"
  target_path="$2"

  if [ -d "$source_path" ] && [ ! -L "$source_path" ]; then
    if [ -L "$target_path" ] || { [ -e "$target_path" ] && [ ! -d "$target_path" ]; }; then
      rm -rf "$target_path"
    fi
    mkdir -p "$target_path"
    for child in "$source_path"/* "$source_path"/.[!.]* "$source_path"/..?*; do
      if [ ! -e "$child" ] && [ ! -L "$child" ]; then
        continue
      fi
      merge_legacy_path "$child" "$target_path/${child##*/}"
    done
    rmdir "$source_path" 2>/dev/null || true
  else
    rm -rf "$target_path"
    mv "$source_path" "$target_path"
  fi
)

LEGACY_NAME=@LEGACY_HOME_FOLDER_SHELL@
LEGACY_DIR="$HOME_DIR/$LEGACY_NAME"
MIGRATE_LEGACY_DATA=0

if [ -n "$LEGACY_NAME" ] && [ -d "$LEGACY_DIR" ] && [ ! -L "$LEGACY_DIR" ] \
    && [ ! -L "$DATA_DIR" ] && [ "$LEGACY_DIR" != "$DATA_DIR" ]; then
  case "${MIGRATE_LEGACY:-}" in
    1) MIGRATE_LEGACY_DATA=1 ;;
    0) ;;
    *)
      if [ ! -e "$DATA_DIR" ]; then
        MIGRATE_LEGACY_DATA=1
      fi
      ;;
  esac
fi

if [ "$MIGRATE_LEGACY_DATA" -eq 1 ]; then
  echo "Migrating legacy launcher data from $LEGACY_DIR to $DATA_DIR"
  mkdir -p "$DATA_DIR"
  for legacy_entry in "$LEGACY_DIR"/* "$LEGACY_DIR"/.[!.]* "$LEGACY_DIR"/..?*; do
    if [ ! -e "$legacy_entry" ] && [ ! -L "$legacy_entry" ]; then
      continue
    fi
    legacy_name="${legacy_entry##*/}"
    case "$legacy_name" in
      launcher|swt) rm -rf "$legacy_entry" ;;
      *) merge_legacy_path "$legacy_entry" "$DATA_DIR/$legacy_name" ;;
    esac
  done
  rmdir "$LEGACY_DIR" 2>/dev/null || true
fi
