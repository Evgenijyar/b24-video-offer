b24-video-offer update 022

Fixes Java compilation error in UniversalVideoDownloader introduced by update 021.

Cause:
KonturVideoDownloader.download(...) declares throws Exception, while UniversalVideoDownloader.download(...) incorrectly declared only IOException, InterruptedException.

Fix:
UniversalVideoDownloader.download(...) now declares throws Exception. Its caller VideoOfferProcessor already catches Exception, so no behavior change is required.

Apply this archive over update 021, replacing files, then run:
  .\mvnw.cmd clean test
