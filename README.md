# QuPath instanseg extension

Development setup:

- Clone Alan's qupath fork https://github.com/alanocallaghan/qupath
  - If you have the main qupath repo cloned already, you could add it as a remote `git add remote alan git@github.com:alanocallaghan/qupath.git && git fetch alan`
  - If you want to make a fresh clone, then continue, the following instructions should still work
  - Switch to branch `instanseg`
    - If you added a remote, then `git checkout instanseg`
- Clone this repo in the same directory as qupath, eg
  - `git clone git@github.com:qupath/qupath-extension-instanseg.git`
  - `git clone git@github.com:qupath/qupath-extension-djl.git`
- Enter the qupath folder `cd qupath`
- Run QuPath using `./gradlew run`
- Open extension (`extensions -> run instanseg`)
- Enter the path to the model `.pt` file(s) in "Downloaded model directory"
- Choose appropriate device/thread/tile size settings
- Run...?
