package cat.iesmanacor.core.controller;

import cat.iesmanacor.common.model.Notificacio;
import cat.iesmanacor.common.model.NotificacioTipus;
import cat.iesmanacor.core.dto.gestib.GrupDto;
import cat.iesmanacor.core.dto.gestib.UsuariDto;
import cat.iesmanacor.core.dto.google.DispositiuDto;
import cat.iesmanacor.core.dto.google.GrupCorreuDto;
import cat.iesmanacor.core.dto.google.LlistatGoogleTipusDto;
import cat.iesmanacor.core.service.*;
import com.google.api.services.directory.model.ChromeOsDevice;
import com.google.api.services.directory.model.User;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

@RestController
public class GoogleSheetsController {

    @Autowired
    private GoogleSpreadsheetService googleSpreadsheetService;

    @Autowired
    private GSuiteService gSuiteService;

    @Autowired
    private LlistatGoogleService llistatGoogleService;

    @Autowired
    private UsuariService usuariService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private Gson gson;

    @Value("${gc.adminUser}")
    private String adminUser;


    @PostMapping("/google/sheets/alumnatpergrup")
    public ResponseEntity alumnatPerGrup(@RequestBody List<GrupDto> grups, HttpServletRequest request) throws GeneralSecurityException, IOException {
        Claims claims = tokenManager.getClaims(request);
        String myEmail = (String) claims.get("email");

        UsuariDto myUser = usuariService.findByEmail(myEmail);

        Spreadsheet spreadsheet = googleSpreadsheetService.alumnesGrup(grups, myUser.getGsuiteEmail());

        llistatGoogleService.save(spreadsheet.getSpreadsheetId(), spreadsheet.getProperties().getTitle(), spreadsheet.getSpreadsheetUrl(), LlistatGoogleTipusDto.ALUMNES_PER_GRUP, myUser);

        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Llistat desat amb èxit");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);

        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

    @PostMapping("/google/sheets/usuarisgrupcorreu")
    public ResponseEntity usuarisPerGrupCorreu(@RequestBody List<GrupCorreuDto> grupsCoreu, HttpServletRequest request) throws GeneralSecurityException, IOException {
        Claims claims = tokenManager.getClaims(request);
        String myEmail = (String) claims.get("email");

        UsuariDto myUser = usuariService.findByEmail(myEmail);

        Spreadsheet spreadsheet = googleSpreadsheetService.usuarisGrupCorreu(grupsCoreu, myUser.getGsuiteEmail());

        llistatGoogleService.save(spreadsheet.getSpreadsheetId(), spreadsheet.getProperties().getTitle(), spreadsheet.getSpreadsheetUrl(), LlistatGoogleTipusDto.USUARIS_PER_GRUPCORREU, myUser);

        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Llistat desat amb èxit");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);

        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

    @PostMapping("/google/sheets/usuarisdispositiu")
    public ResponseEntity usuarisPerDispositiu(HttpServletRequest request) throws GeneralSecurityException, IOException, InterruptedException {
        Claims claims = tokenManager.getClaims(request);
        String myEmail = (String) claims.get("email");

        UsuariDto myUser = usuariService.findByEmail(myEmail);

        List<UsuariDto> usuaris = usuariService.findAll();
        User my = gSuiteService.getUserById(this.adminUser);
        List<ChromeOsDevice> chromeOsDevices = gSuiteService.getChromeOSDevicesByUser(my);

        List<UsuariDto> usuarisChromebook = new ArrayList<>();
        List<UsuariDto> usuarisNoChromebook = new ArrayList<>();
        List<DispositiuDto> dispositiusAssignats = new ArrayList<>();
        List<DispositiuDto> dispositiusNoAssignats = new ArrayList<>();

        int i=1;
        for(ChromeOsDevice chromeOsDevice: chromeOsDevices){
            DispositiuDto dispositiu = new DispositiuDto();
            dispositiu.setIdDispositiu(chromeOsDevice.getDeviceId());
            dispositiu.setEstat(chromeOsDevice.getStatus());
            dispositiu.setModel(chromeOsDevice.getModel());
            dispositiu.setMacAddress(chromeOsDevice.getMacAddress());
            dispositiu.setOrgUnitPath(chromeOsDevice.getOrgUnitPath());
            dispositiu.setSerialNumber(chromeOsDevice.getSerialNumber());

            List<ChromeOsDevice.RecentUsers> recentUsers = chromeOsDevice.getRecentUsers();
            if(recentUsers == null || recentUsers.isEmpty() || recentUsers.get(0) == null){
                dispositiusNoAssignats.add(dispositiu);
            } else {
                //Usuari més recent
                String email = recentUsers.get(0).getEmail();
                UsuariDto usuari = usuariService.findByEmail(email);

                if (usuari != null) {
                    dispositiu.setUsuari(usuari);
                    usuari.getDispositius().add(dispositiu);
                    if(!usuarisChromebook.contains(usuari)){
                        usuarisChromebook.add(usuari);
                    }
                    dispositiusAssignats.add(dispositiu);
                } else {
                    dispositiusNoAssignats.add(dispositiu);
                }
            }
        }

        for(UsuariDto usuari: usuaris){
            if(!usuarisChromebook.contains(usuari)){
                usuarisNoChromebook.add(usuari);
            }
        }


        System.out.println("En total hi ha"+chromeOsDevices.size());
        System.out.println("Usuaris totals:"+usuaris.size());
        System.out.println("Usuaris chromebook"+usuarisChromebook.size());
        System.out.println("Usuaris no chromebook"+usuarisNoChromebook.size());
        System.out.println("Dispositius no assignats"+dispositiusNoAssignats.size());

        Spreadsheet spreadsheet = googleSpreadsheetService.usuarisDispositiu(dispositiusAssignats, dispositiusNoAssignats, usuarisChromebook, usuarisNoChromebook,myUser.getGsuiteEmail());

        llistatGoogleService.save(spreadsheet.getSpreadsheetId(), spreadsheet.getProperties().getTitle(), spreadsheet.getSpreadsheetUrl(), LlistatGoogleTipusDto.USUARIS_PER_DISPOSITIU, myUser);

        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Llistat desat amb èxit");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);

        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

    @PostMapping("/google/sheets/usuariscustom")
    public ResponseEntity usuarisCustom(@RequestBody String json, HttpServletRequest request) throws GeneralSecurityException, IOException {
        Claims claims = tokenManager.getClaims(request);
        String myEmail = (String) claims.get("email");

        UsuariDto myUser = usuariService.findByEmail(myEmail);

        JsonArray usuarisJSON = gson.fromJson(json, JsonArray.class);
        List<UsuariDto> usuaris = new ArrayList<>();
        for(JsonElement usuariJSON: usuarisJSON){
            Long id = usuariJSON.getAsJsonObject().get("id").getAsLong();
            UsuariDto usuari = usuariService.findById(id);
            usuaris.add(usuari);
        }

        Spreadsheet spreadsheet = googleSpreadsheetService.usuarisCustom(usuaris, myUser.getGsuiteEmail());

        llistatGoogleService.save(spreadsheet.getSpreadsheetId(), spreadsheet.getProperties().getTitle(), spreadsheet.getSpreadsheetUrl(), LlistatGoogleTipusDto.USUARIS_CUSTOM, myUser);

        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Llistat desat amb èxit");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);

        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

}