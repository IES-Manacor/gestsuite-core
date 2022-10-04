package cat.iesmanacor.core.repository.gestib;

import cat.iesmanacor.core.model.gestib.Sessio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessioRepository extends JpaRepository<Sessio, Long> {
    List<Sessio> findAllByGestibProfessor(String professor);

    List<Sessio> findAllByGestibAlumne(String alumne);
}
