package ru.abs7.videooffer.publicpage;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferService;
import ru.abs7.videooffer.offer.VideoOfferStatus;
import java.io.*;
import java.nio.file.*;
import java.util.Map;

@RestController
public class PublicOfferController {
    private static final int BUFFER_SIZE=1024*1024;
    private final VideoOfferService service;
    public PublicOfferController(VideoOfferService service){this.service=service;}

    @GetMapping(value="/o/{token}", produces=MediaType.TEXT_HTML_VALUE)
    public byte[] page(@PathVariable String token) throws IOException {
        service.getByToken(token);
        try(InputStream in=new ClassPathResource("static/offer.html").getInputStream()){return in.readAllBytes();}
    }

    @GetMapping("/api/public/offers/{token}")
    public Map<String,Object> data(@PathVariable String token){
        VideoOffer v=service.getByToken(token);
        return Map.of("token",v.getPublicToken(),"status",v.getStatus(),"text",v.getAccompanyingText()==null?"":v.getAccompanyingText(),"ready",v.getStatus()==VideoOfferStatus.READY);
    }

    @GetMapping("/media/{token}")
    public void media(@PathVariable String token, @RequestHeader(value="Range",required=false) String range, HttpServletResponse response) throws IOException {
        VideoOffer v=service.getByToken(token);
        if(v.getStatus()!=VideoOfferStatus.READY || v.getVideoFilePath()==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path=Path.of(v.getVideoFilePath());
        if(!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long size=Files.size(path), start=0, end=size-1;
        boolean partial=range!=null && range.startsWith("bytes=");
        if(partial){
            String[] pieces=range.substring(6).split("-",2);
            try { start=Long.parseLong(pieces[0]); if(pieces.length>1&&!pieces[1].isBlank()) end=Long.parseLong(pieces[1]); }
            catch(NumberFormatException e){response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);response.setHeader(HttpHeaders.CONTENT_RANGE,"bytes */"+size);return;}
            end=Math.min(end,size-1);
            if(start<0||start>end||start>=size){response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);response.setHeader(HttpHeaders.CONTENT_RANGE,"bytes */"+size);return;}
        }
        long length=end-start+1;
        response.setStatus(partial?HttpServletResponse.SC_PARTIAL_CONTENT:HttpServletResponse.SC_OK);
        response.setContentType("video/mp4");
        response.setHeader(HttpHeaders.ACCEPT_RANGES,"bytes");
        response.setHeader(HttpHeaders.CONTENT_LENGTH,Long.toString(length));
        if(partial) response.setHeader(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+size);
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r"); OutputStream out=response.getOutputStream()){
            file.seek(start); byte[] buffer=new byte[BUFFER_SIZE]; long remaining=length;
            while(remaining>0){int read=file.read(buffer,0,(int)Math.min(buffer.length,remaining));if(read<0)break;out.write(buffer,0,read);remaining-=read;}
        }
    }
}
