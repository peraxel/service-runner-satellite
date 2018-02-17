/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.felth.service.starter.satellite;

import com.auth0.jwt.Algorithm;
import com.auth0.jwt.JWTSigner;
import static com.auth0.jwt.pem.PemReader.readPrivateKey;
import static com.auth0.jwt.pem.PemReader.readPublicKey;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author jnj112
 */
public class TokenGenerator {
    public final static int TOKEN_TTL = 60;
    private static final Logger LOG = Logger.getLogger(TokenGenerator.class.getName());
    PrivateKey privateKey;
    String iss;
    
    public TokenGenerator(String privateKeyPath, String iss) {
        this.iss = iss;
        
        try {
            privateKey = readPrivateKey(privateKeyPath);
        } catch (NoSuchProviderException | NoSuchAlgorithmException | IOException | InvalidKeySpecException ex) {
            LOG.log(Level.SEVERE, "TokenGenerator init failed", ex);
        }
    }
    
    public String generate(String aud) {
        long iat = ZonedDateTime.now(ZoneId.of("Z")).toEpochSecond();
        long exp = iat + TOKEN_TTL;
        
        JWTSigner signer = new JWTSigner(privateKey);
        
        Map<String,Object> claims = new HashMap<>();
        claims.put("aud", aud);
        claims.put("exp", exp);
        claims.put("iss", iss);
        claims.put("iat", iat);
        
        JWTSigner.Options o = new JWTSigner.Options();
        o.setAlgorithm(Algorithm.RS256);
        return signer.sign(claims, o);
    }
}
