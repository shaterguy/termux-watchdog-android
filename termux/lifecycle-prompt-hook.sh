# BEGIN TERMUX_WATCHDOG_LIFECYCLE_V1 (ordinary interactive login only)
if [[ $- == *i* ]] && shopt -q login_shell; then
  _tw_lifecycle_ticket="$("$HOME/bin/termux-watchdog-lifecycle-internal" login-ticket 2>/dev/null)" || _tw_lifecycle_ticket=
  if [[ $_tw_lifecycle_ticket =~ ^[0-9a-f-]{36}\ [1-9][0-9]*$ ]]; then
    _tw_lifecycle_first_prompt() {
      local previous=$?
      local ticket="${_tw_lifecycle_ticket-}"
      unset _tw_lifecycle_ticket
      if [[ $ticket =~ ^([0-9a-f-]{36})\ ([1-9][0-9]*)$ ]]; then
        "$HOME/bin/termux-watchdog-lifecycle-internal" login-complete "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" >/dev/null 2>&1 || :
      fi
      return "$previous"
    }
    if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) =~ ^declare\ -[^[:space:]]*a[^[:space:]]*\  ]]; then
      if [[ " ${PROMPT_COMMAND[*]} " != *' _tw_lifecycle_first_prompt '* ]]; then
        PROMPT_COMMAND=(_tw_lifecycle_first_prompt "${PROMPT_COMMAND[@]}")
      fi
    elif [[ ${PROMPT_COMMAND-} != *'_tw_lifecycle_first_prompt'* ]]; then
      PROMPT_COMMAND="_tw_lifecycle_first_prompt${PROMPT_COMMAND:+; $PROMPT_COMMAND}"
    fi
  fi
fi
# END TERMUX_WATCHDOG_LIFECYCLE_V1
