TODO:
[ ] Notification kinda not work when "annoying" is turned on, take a look there
[x] Change 'UploaderService' to diffrent instance per requests.
[x] Show server response
[x] Server error isn't working neatly
[ ] UI is still wonnkie


NOTE: `file` is a custom object not to be confused with `File`
NOTE: progress is also a custom object (with suitable methods)

Service:
- Handles upload ( drains queue ) ( can be several files ) ( on command, with a file object )
- ~~Expose a method to get progress~~ Insted use an inteface progressListener
- Expose a method to initiate upload (via appending to a queue)
- OnBind should return a binder that will be responsable for exposing the Expose methods
- Should it be Foreground service? heck yeah, the service will manage the notifications so it should be Foreground.

Activity:
- Start the Service
- Bind to the Service
- Start a watcher ( or monitor ) the Service's progress via repeadtly getting the progress and updating UI
    - issue is "repeadtly", will it slow down my ui?
    - also garenties that updates will be smooth ( i hope )
- How should the upload be handled? (user need to command the Service to start upload)
- should the Activity send all files to upload at once (prob introduce race conditions) or feed them one at a time
- this Activity can be killed, on command (obviously) and the upload will go on

progress:
- total
- done
- step
- currentFile:
    - name
    - title
    - desc
    - size
    - etc
