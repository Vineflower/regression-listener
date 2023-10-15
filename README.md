# Regression Listener

This is the repository for the Vineflower regression listener, a program that accepts push webhook events for Vineflower
and creates a diff from the decompiler output before and after. The listener contains an http server that listens for
POST requests and handles accordingly. After a validated request has been filed, the listener clones the repo, builds
the before and after jars, and runs them on a specified set of files in `dlmanifest.txt` to create a diff.

### How to use
1. `./gradlew build`. You'll get two jars: the main jar in `./build/libs` and the decomp utility in `./src/jardecomp/build/libs`.
2. Put both of these jars in the same directory next to each other.
3. Create a file called `dlmanifest.txt`, where each line contains a link to a jar to download.
4. Run the listener: `java [OPTIONS] -jar regression-listener.jar` Make sure to set all of the options required. Check [Main.java](./src/main/java/org/vineflower/regressionlistener/Main.java) for a list.