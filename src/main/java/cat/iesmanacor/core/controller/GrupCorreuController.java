package cat.iesmanacor.core.controller;

import cat.iesmanacor.common.model.Notificacio;
import cat.iesmanacor.common.model.NotificacioTipus;
import cat.iesmanacor.core.dto.gestib.*;
import cat.iesmanacor.core.dto.google.GrupCorreuDto;
import cat.iesmanacor.core.dto.google.GrupCorreuTipusDto;
import cat.iesmanacor.core.service.*;
import com.google.api.services.directory.model.Group;
import com.google.api.services.directory.model.Member;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class GrupCorreuController {

    @Autowired
    private UsuariService usuariService;

    @Autowired
    private GrupService grupService;

    @Autowired
    private GSuiteService gSuiteService;

    @Autowired
    private GrupCorreuService grupCorreuService;

    @Autowired
    private SessioService sessioService;

    @Autowired
    private SubmateriaService submateriaService;

    @Autowired
    private ActivitatService activitatService;

    @Autowired
    private DepartamentService departamentService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private Gson gson;

    @Value("${gc.adminDeveloper}")
    private String adminDeveloper;

    @GetMapping("/grupcorreu/llistat")
    public ResponseEntity<List<GrupCorreuDto>> getGrups(HttpServletRequest request) throws GeneralSecurityException, IOException, InterruptedException {

        /*
        Sincronitzaci?? GSuite -> BBDD
        Si el grup NO existeix creem el grup a la BBDD i associem els usuaris
        Si el grup SI existeix actualitzem els membres de la BBDD
         */
        List<Group> groups = gSuiteService.getGroups();

        for (Group grup : groups) {

            GrupCorreuDto grupCorreu = grupCorreuService.findByEmail(grup.getEmail());

            if (grupCorreu == null) {
                //Creem el grup de correu a la BBDD
                grupCorreuService.save(null, grup.getName(), grup.getEmail(), grup.getDescription(), GrupCorreuTipusDto.GENERAL);
            }
        }

        List<GrupCorreuDto> grupsCorreu = grupCorreuService.findAll();

        return new ResponseEntity<>(grupsCorreu, HttpStatus.OK);
    }


    @GetMapping("/grupcorreu/grupambusuaris/{id}")
    public ResponseEntity getGrupAmbUsuaris(@PathVariable("id") String idgrup) throws GeneralSecurityException, IOException {
        try {
        /*
        Sincronitzaci?? GSuite -> BBDD
        Si el grup NO existeix creem el grup a la BBDD i associem els usuaris
        Si el grup SI existeix actualitzem els membres de la BBDD
         */
            Group grup = gSuiteService.getGroupById(idgrup);

            GrupCorreuDto grupCorreu = grupCorreuService.findByEmail(grup.getEmail());

            if (grupCorreu == null) {
                //Creem el grup de correu a la BBDD
                grupCorreu = grupCorreuService.save(null, grup.getName(), grup.getEmail(), grup.getDescription(), GrupCorreuTipusDto.GENERAL);
            } else {
                //Esborrem els usuaris del grup de correu
                grupCorreuService.esborrarUsuarisGrupCorreu(grupCorreu);
                grupCorreu.setUsuaris(new HashSet<>());

                //Esborrem els grups de correu del grup de correu
                grupCorreuService.esborrarGrupsCorreuGrupCorreu(grupCorreu);
                grupCorreu.setGrupCorreus(new HashSet<>());
            }

            //Afegim els membres del grup a la BBDD
            List<Member> members = gSuiteService.getMembers(grupCorreu.getGsuiteEmail());

            for (Member member : members) {
                UsuariDto usuari = usuariService.findByEmail(member.getEmail());
                GrupCorreuDto grupCorreuMember = grupCorreuService.findByEmail(member.getEmail());
                if (usuari != null) {
                    grupCorreuService.insertUsuari(grupCorreu, usuari);
                    grupCorreu.getUsuaris().add(usuari);
                }
                if (grupCorreuMember != null) {
                    grupCorreuService.insertGrupCorreu(grupCorreu, grupCorreuMember);
                    grupCorreu.getGrupCorreus().add(grupCorreuMember);
                }
            }

            grupCorreuService.save(grupCorreu);

            return new ResponseEntity<>(grupCorreu, HttpStatus.OK);
        } catch (Exception e){
            Notificacio notificacio = new Notificacio();
            notificacio.setNotifyMessage("Error desconegut: " + e.getMessage());
            notificacio.setNotifyType(NotificacioTipus.ERROR);
            return new ResponseEntity<>(notificacio, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/grupcorreu/grupsusuari/{id}")
    public ResponseEntity<List<GrupCorreuDto>> getGrupsCorreuUsuari(@PathVariable("id") String idUsuari, HttpServletRequest request) throws Exception {
        Claims claims = tokenManager.getClaims(request);
        String myEmail = (String) claims.get("email");

        UsuariDto myUser = usuariService.findByEmail(myEmail);
        UsuariDto usuari = usuariService.findById(Long.valueOf(idUsuari));

        List<GrupCorreuDto> grupsCorreu = new ArrayList<>();
        //Si l'usuari que fa la consulta ??s el mateix o b?? si t?? rol de cap d'estudis, director o administrador
        if (
                myEmail.equals(this.adminDeveloper) ||
                (
                    myUser != null && usuari != null && myUser.getGsuiteEmail() != null && usuari.getGsuiteEmail() != null &&
                    (myUser.getGsuiteEmail().equals(usuari.getGsuiteEmail()) || myUser.getRols().contains(RolDto.ADMINISTRADOR) || myUser.getRols().contains(RolDto.DIRECTOR) || myUser.getRols().contains(RolDto.CAP_ESTUDIS))
                )
        ) {
            grupsCorreu.addAll(grupCorreuService.findByUsuari(usuari));
        } else {
            throw new Exception("Sense permisos");
        }

        return new ResponseEntity<>(grupsCorreu, HttpStatus.OK);
    }

    @PostMapping("/grupcorreu/addmember")
    public ResponseEntity<Notificacio> addMember(@RequestBody String json) throws InterruptedException {
        JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

        String iduser = jsonObject.get("user").getAsString();
        String emailGroup = jsonObject.get("group").getAsString();

        UsuariDto usuari = usuariService.findById(Long.valueOf(iduser));
        GrupCorreuDto grupCorreu = grupCorreuService.findByEmail(emailGroup);

        grupCorreuService.insertUsuari(grupCorreu, usuari);
        gSuiteService.createMember(usuari.getGsuiteEmail(), grupCorreu.getGsuiteEmail());

        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Usuari "+usuari.getGsuiteEmail()+" afegit correctament al grup");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);
        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

    @PostMapping("/grupcorreu/removemember")
    public ResponseEntity<Notificacio> removeMember(@RequestBody String json) throws InterruptedException {
        JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

        String iduser = jsonObject.get("user").getAsString();
        String emailGroup = jsonObject.get("group").getAsString();

        UsuariDto usuari = usuariService.findById(Long.valueOf(iduser));
        GrupCorreuDto grupCorreu = grupCorreuService.findByEmail(emailGroup);

        grupCorreuService.esborrarUsuari(grupCorreu, usuari);
        gSuiteService.deleteMember(usuari.getGsuiteEmail(), grupCorreu.getGsuiteEmail());

        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Usuari "+usuari.getGsuiteEmail()+" esborrat correctament del grup");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);
        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

    @PostMapping("/grupcorreu/desar")
    public ResponseEntity<Notificacio> saveGrup(@RequestBody String json) throws InterruptedException {
        JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

        Long idGrupCorreu;
        String tipus = jsonObject.get("grupCorreuTipus").getAsString();
        String descripcio = "";
        String email = jsonObject.get("gsuiteEmail").getAsString();
        String nom = "";
        JsonArray jsonUsuaris = jsonObject.get("usuaris").getAsJsonArray();
        JsonArray jsonGrupsCorreu = jsonObject.get("grupCorreus").getAsJsonArray();
        JsonArray jsonGrups = null;
        if(jsonObject.get("grups")!=null && !jsonObject.get("grups").isJsonNull()) {
            jsonGrups = jsonObject.get("grups").getAsJsonArray();
        }


        GrupCorreuDto grupCorreu = new GrupCorreuDto();

        if(jsonObject.get("idgrup")!=null && !jsonObject.get("idgrup").isJsonNull()){
            idGrupCorreu = jsonObject.get("idgrup").getAsLong();
            grupCorreu = grupCorreuService.findById(idGrupCorreu);
        }

        if(jsonObject.get("gsuiteDescripcio")!=null && !jsonObject.get("gsuiteDescripcio").isJsonNull()){
            descripcio = jsonObject.get("gsuiteDescripcio").getAsString();
        }

        if(jsonObject.get("gsuiteNom")!=null && !jsonObject.get("gsuiteNom").isJsonNull()){
            nom = jsonObject.get("gsuiteNom").getAsString();
        }

        grupCorreu.setGrupCorreuTipus(GrupCorreuTipusDto.valueOf(tipus));
        grupCorreu.setGsuiteEmail(email);
        grupCorreu.setGsuiteNom(nom);
        grupCorreu.setGsuiteDescripcio(descripcio);

        if (grupCorreu.getIdgrup() == null) {
            gSuiteService.createGroup(grupCorreu.getGsuiteEmail(), grupCorreu.getGsuiteNom(), grupCorreu.getGsuiteDescripcio());
        } else {
            gSuiteService.updateGroup(grupCorreu.getGsuiteEmail(), grupCorreu.getGsuiteNom(), grupCorreu.getGsuiteDescripcio());
        }

        //USUARIS
        List<UsuariDto> usuaris = new ArrayList<>();
        for(JsonElement jsonUsuari: jsonUsuaris){
            Long idUsuari = jsonUsuari.getAsJsonObject().get("idusuari").getAsLong();
            UsuariDto usuari = usuariService.findById(idUsuari);
            if(usuari!=null){
                usuaris.add(usuari);
            }
        }

        //GRUPS
        List<GrupDto> grups = new ArrayList<>();
        if(jsonGrups!=null) {
            for (JsonElement jsonGrup : jsonGrups) {
                if(
                        jsonGrup!=null && !jsonGrup.isJsonNull() &&
                        jsonGrup.getAsJsonObject()!=null && !jsonGrup.getAsJsonObject().isJsonNull() &&
                        jsonGrup.getAsJsonObject().get("value") != null && !jsonGrup.getAsJsonObject().get("value").isJsonNull()
                ) {
                    String gestibIdGrup = jsonGrup.getAsJsonObject().get("value").getAsString();
                    GrupDto grup = grupService.findByGestibIdentificador(gestibIdGrup);
                    if (grup != null) {
                        grups.add(grup);
                    }
                }
            }
        }

        //GRUPS DE CORREU
        List<GrupCorreuDto> grupsCorreu = new ArrayList<>();
        for(JsonElement jsonGrupCorreu: jsonGrupsCorreu){
            Long idGrupCorreuAssociat = jsonGrupCorreu.getAsJsonObject().get("idgrup").getAsLong();
            GrupCorreuDto grupCorreuAssociat = grupCorreuService.findById(idGrupCorreuAssociat);
            if(grupCorreuAssociat!=null){
                grupsCorreu.add(grupCorreuAssociat);
            }
        }


        //SINCRONITZACI?? AMB LA BBDD

        GrupCorreuDto grupCorreuSaved = grupCorreuService.save(grupCorreu);

        //Esborrem els usuaris del grup de correu
        grupCorreuService.esborrarUsuarisGrupCorreu(grupCorreuSaved);
        grupCorreuSaved.setUsuaris(new HashSet<>());

        //Esborrem els grups del grup de correu
        grupCorreuService.esborrarGrupsGrupCorreu(grupCorreuSaved);
        grupCorreuSaved.setGrups(new HashSet<>());

        //Esborrem els grups de correu del grup de correu
        grupCorreuService.esborrarGrupsCorreuGrupCorreu(grupCorreuSaved);
        grupCorreuSaved.setGrupCorreus(new HashSet<>());


        //Tornem a inserir els usuaris
        for (UsuariDto usuari : usuaris) {
            grupCorreuService.insertUsuari(grupCorreuSaved, usuari);
        }
        grupCorreuSaved.setUsuaris(new HashSet<>(usuaris));

        //Tornem a inserir els grups
        for(GrupDto grup: grups){
            grupCorreuService.insertGrup(grupCorreuSaved, grup);
        }
        grupCorreuSaved.setGrups(new HashSet<>(grups));

        //Tornem a inserir els grups
        for (GrupCorreuDto grupCorreuMember : grupsCorreu) {
            grupCorreuService.insertGrupCorreu(grupCorreuSaved, grupCorreuMember);
        }
        grupCorreuSaved.setGrupCorreus(new HashSet<>(grupsCorreu));


        //Desem els canvis
        grupCorreuService.save(grupCorreuSaved);

        //Sincronitzem amb GSuite
        List<Member> members = gSuiteService.getMembers(grupCorreuSaved.getGsuiteEmail());

        for (Member member : members) {
            gSuiteService.deleteMember(member.getEmail(), grupCorreuSaved.getGsuiteEmail());
        }

        //Tornem a inserir els usuaris
        for (UsuariDto usuari : usuaris) {
            gSuiteService.createMember(usuari.getGsuiteEmail(), grupCorreuSaved.getGsuiteEmail());
        }

        //Tornem a inserir els grups
        for (GrupCorreuDto grupCorreuMember : grupsCorreu) {
            gSuiteService.createMember(grupCorreuMember.getGsuiteEmail(), grupCorreuSaved.getGsuiteEmail());
        }


        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Grup desat correctament");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);
        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

    @PostMapping("/grupcorreu/autoemplenar")
    public ResponseEntity<Notificacio> autoemplenaGrup(@RequestBody GrupCorreuDto grupCorreu) throws InterruptedException {
        if (grupCorreu.getIdgrup() == null) {
            grupCorreu.setGrupCorreuTipus(GrupCorreuTipusDto.GENERAL);
            gSuiteService.createGroup(grupCorreu.getGsuiteEmail(), grupCorreu.getGsuiteNom(), grupCorreu.getGsuiteDescripcio());
        } else {
            gSuiteService.updateGroup(grupCorreu.getGsuiteEmail(), grupCorreu.getGsuiteNom(), grupCorreu.getGsuiteDescripcio());
        }

        GrupCorreuDto grupCorreuSaved = grupCorreuService.save(grupCorreu);

        List<UsuariDto> usuarisGrup = new ArrayList<>();
        if (grupCorreu.getGrupCorreuTipus().equals(GrupCorreuTipusDto.ALUMNAT)) {
            for(GrupDto grupDto: grupCorreuSaved.getGrups()) {
                List<UsuariDto> alumnes = usuariService.findUsuarisByGestibGrup(grupDto.getGestibIdentificador());
                usuarisGrup.addAll(alumnes.stream().filter(UsuariDto::getActiu).collect(Collectors.toList()));
            }
        } else if (grupCorreu.getGrupCorreuTipus().equals(GrupCorreuTipusDto.PROFESSORAT)) {
            List<UsuariDto> professors = usuariService.findProfessors();
            for (UsuariDto profe : professors) {
                System.out.println("Usuari:" + profe.getGsuiteFullName() + "Email:" + profe.getGsuiteEmail());
                List<SessioDto> sessions = sessioService.findSessionsProfessor(profe);
                Set<String> grupsProfe = new HashSet<>();
                for (SessioDto sessio : sessions) {
                    String codiGrup = sessio.getGestibGrup();
                    if (codiGrup != null && !codiGrup.isEmpty()) {
                        grupsProfe.add(codiGrup);
                    }
                }
                for (String grupProfe : grupsProfe) {
                    for(GrupDto grupDto: grupCorreuSaved.getGrups()) {
                        if (grupProfe.equals(grupDto.getGestibIdentificador()) && profe.getActiu()) {
                            usuarisGrup.add(profe);
                        }
                    }
                }
            }
        } else if (grupCorreu.getGrupCorreuTipus().equals(GrupCorreuTipusDto.TUTORS_FCT)) {
            List<UsuariDto> professors = usuariService.findProfessors();
            for (UsuariDto profe : professors) {
                System.out.println("Usuari:" + profe.getGsuiteFullName() + "Email:" + profe.getGsuiteEmail());
                List<SessioDto> sessions = sessioService.findSessionsProfessor(profe);
                for (SessioDto sessio : sessions) {
                    String codiGestibSubmateria = sessio.getGestibSubmateria();
                    if (codiGestibSubmateria != null && !codiGestibSubmateria.isEmpty()) {
                        SubmateriaDto submateria = submateriaService.findByGestibIdentificador(codiGestibSubmateria);

                        if (submateria != null && submateria.getGestibNom() != null && submateria.getGestibNomCurt() != null &&
                                (
                                        submateria.getGestibNom().contains("Formaci?? en centres de treball") ||
                                                submateria.getGestibNom().contains("FCT") ||
                                                submateria.getGestibNomCurt().contains("Formaci?? en centres de treball") ||
                                                submateria.getGestibNomCurt().contains("FCT")
                                )
                                && profe.getActiu()
                        ) {
                            usuarisGrup.add(profe);
                        }
                    }
                    String codiGestibActivitat = sessio.getGestibActivitat();
                    if (codiGestibActivitat != null && !codiGestibActivitat.isEmpty()) {
                        ActivitatDto activitat = activitatService.findByGestibIdentificador(codiGestibActivitat);

                        if (activitat != null && activitat.getGestibNom() != null && activitat.getGestibNomCurt() != null &&
                                (
                                        activitat.getGestibNom().contains("Formaci?? en centres de treball") ||
                                                activitat.getGestibNom().contains("FCT") ||
                                                activitat.getGestibNomCurt().contains("Formaci?? en centres de treball") ||
                                                activitat.getGestibNomCurt().contains("FCT")

                                )
                                && profe.getActiu()
                        ) {
                            usuarisGrup.add(profe);
                        }
                    }
                }
            }
        } else if (grupCorreu.getGrupCorreuTipus().equals(GrupCorreuTipusDto.DEPARTAMENT)) {
            List<UsuariDto> professors = usuariService.findProfessors();
            for (UsuariDto profe : professors) {
                String departamentCodi = profe.getGestibDepartament();
                DepartamentDto departament = departamentService.findByGestibIdentificador(departamentCodi);
                List<GrupCorreuDto> correusDepartament = grupCorreuService.findByDepartament(departament);
                for(GrupCorreuDto grupCorreuDepartament: correusDepartament) {
                    if (departament != null && grupCorreuDepartament.getGsuiteEmail().equals(grupCorreuSaved.getGsuiteEmail()) && profe.getActiu()) {
                        usuarisGrup.add(profe);
                    }
                }
            }
        } else if (grupCorreu.getGrupCorreuTipus().equals(GrupCorreuTipusDto.TUTORS)) {
            List<UsuariDto> professors = usuariService.findProfessors();
            List<GrupDto> grups = grupService.findAll();
            for(GrupDto grup: grups) {
                for(GrupDto grupGrupCorreu: grupCorreu.getGrups()) {
                    if(grup.getIdgrup().equals(grupGrupCorreu.getIdgrup())) {
                        String tutor1 = "";
                        if (grup.getGestibTutor1() != null && !grup.getGestibTutor1().isEmpty()) {
                            tutor1 = grup.getGestibTutor1();
                        }
                        String tutor2 = "";
                        if (grup.getGestibTutor2() != null && !grup.getGestibTutor2().isEmpty()) {
                            tutor2 = grup.getGestibTutor2();
                        }
                        String tutor3 = "";
                        if (grup.getGestibTutor3() != null && !grup.getGestibTutor3().isEmpty()) {
                            tutor3 = grup.getGestibTutor3();
                        }
                        for (UsuariDto profe : professors) {
                            if (profe.getGestibCodi() != null && !profe.getGestibCodi().isEmpty() && (profe.getGestibCodi().equals(tutor1) || profe.getGestibCodi().equals(tutor2) || profe.getGestibCodi().equals(tutor3))) {
                                usuarisGrup.add(profe);
                            }
                        }
                    }
                }
            }
        }

        //Esborrem els usuaris del grup de correu
        grupCorreuService.esborrarUsuarisGrupCorreu(grupCorreuSaved);
        grupCorreuSaved.setUsuaris(new HashSet<>());

        //Esborrem els grups de correu del grup de correu
        grupCorreuService.esborrarGrupsCorreuGrupCorreu(grupCorreuSaved);
        grupCorreuSaved.setGrupCorreus(new HashSet<>());


        //Tornem a inserir els usuaris
        for (UsuariDto usuari : usuarisGrup) {
            grupCorreuService.insertUsuari(grupCorreuSaved, usuari);
        }
        grupCorreuSaved.setUsuaris(new HashSet<>(usuarisGrup));


        //Desem els canvis
        grupCorreuService.save(grupCorreuSaved);

        //Sincronitzem amb GSuite
        List<Member> members = gSuiteService.getMembers(grupCorreuSaved.getGsuiteEmail());

        for (Member member : members) {
            gSuiteService.deleteMember(member.getEmail(), grupCorreuSaved.getGsuiteEmail());
        }

        //Tornem a inserir els usuaris
        for (UsuariDto usuari : usuarisGrup) {
            gSuiteService.createMember(usuari.getGsuiteEmail(), grupCorreuSaved.getGsuiteEmail());
        }


        Notificacio notificacio = new Notificacio();
        notificacio.setNotifyMessage("Grup autoemplenat correctament");
        notificacio.setNotifyType(NotificacioTipus.SUCCESS);
        return new ResponseEntity<>(notificacio, HttpStatus.OK);
    }

}