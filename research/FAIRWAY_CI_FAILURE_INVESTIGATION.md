# Fairway research CI failure investigation

**Date:** 2026-11-24  
**Branch:** `research/generic-course-pipeline`

## Findings

The research-pipeline workflow previously completed successfully as run 140 on commit `a29fae431cbe0abe2981ba76b9320a11e9d5bfdf`. Later runs 141-146 failed. The failures are not yet attributable to Python code because GitHub exposes no executed step records for these jobs.

To isolate the failure from repository checkout, Python setup, dependency installation, and test commands, a separate workflow was added:

- `.github/workflows/research-runner-diagnostic.yml`
- It has one job with two trivial shell steps: print runner identity/time and print Python version.
- It was first run on `ubuntu-latest` (run 1, ID `45426372806`, commit `a316924a14f2167a1a686d998a9dc9c852f2481a`).
- It was then changed to `ubuntu-22.04` and rerun (run 2, ID `45426902981`, commit `0006e502ab8c2f0eaab8215422c0a2a5c47af979`).

Both diagnostic runs concluded **failure**. For both, GitHub's jobs response contained a completed failed job but `steps: null`; the job logs endpoint returned a missing log blob. Therefore, failure occurred before any user-defined diagnostic step was recorded. Changing the runner image did not resolve it.

## What this establishes

- The failure is not caused by the fairway reference evaluator's Python imports or tests: the minimal workflow does not import or execute project code.
- The failure is not specific to `ubuntu-latest`: `ubuntu-22.04` failed in the same pre-step manner.
- The GitHub Actions service/runner allocation or repository/account Actions configuration is the leading area to investigate.

## What cannot be established from available evidence

The GitHub connector cannot retrieve the failed job's internal runner provisioning diagnostics; the logs are absent. It also does not expose the required repository/account Actions administration controls to inspect or change billing, usage limits, runner availability, or policy restrictions. It would be misleading to claim a definitive root cause or claim that a workflow edit fixed it.

## Required external resolution

The repository owner should open the failed diagnostic runs in GitHub Actions and inspect the job-level annotations/details. If GitHub still provides no runner logs, contact GitHub Support with run IDs `45426372806` and `45426902981`, stating that both a minimal two-step workflow and the research workflow fail before the first step on separate hosted runner images. Also verify repository Actions are enabled and that the account has not exhausted included Actions minutes or hit an account policy restriction.

After runner service is restored, rerun the research pipeline. Only then can compilation, the reference evaluator tests, and existing course association audits be considered verified.

## Status

**Open — blocked outside repository code.** No Android production code or main branch changes were made.
