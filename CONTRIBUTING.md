# Contributing
It's great you're thinking about contributing to make Ondu better!

## LLM policy
Since pretty much the entire codebase is LLM generated right now, so it's fine
to add contributions that are generated too. Just be transparent about it.

## Testing and Linting
Run the unit tests and Android Lint before sending a change; CI runs both and
rejects any lint error:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The lint report is written to `app/build/reports/lint-results-debug.html`.

## License
By contributing, you agree that your contributions will be licensed under the
same license as this project (see `LICENSE`).
