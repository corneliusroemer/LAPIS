package ch.ethz.lapis.api.findaquery;

import ch.ethz.lapis.LapisMain;
import ch.ethz.lapis.api.entity.req.SampleDetailRequest;
import ch.ethz.lapis.api.query.Database;
import ch.ethz.lapis.api.query.MutationStore;
import ch.ethz.lapis.api.query.QueryEngine;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/find-a-query")
public class FindAQueryController {

    private static final ComboPooledDataSource dbPool = LapisMain.dbPool;

    @GetMapping("")
    public List<List<String>> test(SampleDetailRequest request) {
        Database database = Database.getOrLoadInstance(dbPool);
        MutationStore mutationStore = database.getNucMutationStore();
        FindAQuery findAQuery = new FindAQuery(mutationStore);
        QueryEngine queryEngine = new QueryEngine();
        List<Integer> seqIds = queryEngine.filterIds(database, request);
        List<List<Integer>> clusters = findAQuery.kMeans(seqIds, 2);
        List<List<String>> clustersWithGisaidIds = new ArrayList<>();
        String[] gisaidEpiIslColumn = database.getStringColumn(Database.Columns.GISAID_EPI_ISL);
        for (List<Integer> cluster : clusters) {
            clustersWithGisaidIds.add(cluster.stream().map(id -> gisaidEpiIslColumn[id]).collect(Collectors.toList()));
        }

        return clustersWithGisaidIds;
    }

}
